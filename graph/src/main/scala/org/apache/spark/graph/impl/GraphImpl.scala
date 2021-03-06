package org.apache.spark.graph.impl

import scala.collection.JavaConversions._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.ArrayBuilder
import scala.collection.mutable.BitSet


import org.apache.spark.SparkContext._
import org.apache.spark.Partitioner
import org.apache.spark.HashPartitioner 
import org.apache.spark.util.ClosureCleaner

import org.apache.spark.rdd
import org.apache.spark.rdd.RDD


import org.apache.spark.graph._
import org.apache.spark.graph.impl.GraphImpl._
import org.apache.spark.graph.impl.MessageToPartitionRDDFunctions._

/**
 * The Iterator type returned when constructing edge triplets
 */
class EdgeTripletIterator[VD: ClassManifest, ED: ClassManifest](
  val vidToIndex: VertexIdToIndexMap,
  val vertexArray: Array[VD],
  val edgePartition: EdgePartition[ED]) extends Iterator[EdgeTriplet[VD, ED]] {

  private var pos = 0
  private val et = new EdgeTriplet[VD, ED]
  
  override def hasNext: Boolean = pos < edgePartition.size
  override def next() = {
    et.srcId = edgePartition.srcIds(pos)
    // assert(vmap.containsKey(e.src.id))
    et.srcAttr = vertexArray(vidToIndex(et.srcId))
    et.dstId = edgePartition.dstIds(pos)
    // assert(vmap.containsKey(e.dst.id))
    et.dstAttr = vertexArray(vidToIndex(et.dstId))
    et.attr = edgePartition.data(pos)
    pos += 1
    et
  }

  override def toList: List[EdgeTriplet[VD, ED]] = {
    val lb = new mutable.ListBuffer[EdgeTriplet[VD,ED]]
    val currentEdge = new EdgeTriplet[VD, ED]
    for (i <- (0 until edgePartition.size)) {
      currentEdge.srcId = edgePartition.srcIds(i)
      // assert(vmap.containsKey(e.src.id))
      currentEdge.srcAttr = vertexArray(vidToIndex(currentEdge.srcId))
      currentEdge.dstId = edgePartition.dstIds(i)
      // assert(vmap.containsKey(e.dst.id))
      currentEdge.dstAttr = vertexArray(vidToIndex(currentEdge.dstId))
      currentEdge.attr = edgePartition.data(i)
      lb += currentEdge
    }
    lb.toList
  }
} // end of Edge Triplet Iterator



object EdgeTripletBuilder {
  def makeTriplets[VD: ClassManifest, ED: ClassManifest]( 
    localVidMap: RDD[(Pid, VertexIdToIndexMap)],
    vTableReplicatedValues: RDD[(Pid, Array[VD]) ],
    eTable: RDD[(Pid, EdgePartition[ED])]): RDD[EdgeTriplet[VD, ED]] = {
    localVidMap.zipPartitions(vTableReplicatedValues, eTable) {
      (vidMapIter, replicatedValuesIter, eTableIter) =>
      val (_, vidToIndex) = vidMapIter.next()
      val (_, vertexArray) = replicatedValuesIter.next()
      val (_, edgePartition) = eTableIter.next()
      new EdgeTripletIterator(vidToIndex, vertexArray, edgePartition)
    }
  }
}


//   {
//     val iterFun = (iter: Iterator[(Pid, ((VertexIdToIndexMap, Array[VD]), EdgePartition[ED]))]) => {
//       val (pid, ((vidToIndex, vertexArray), edgePartition)) = iter.next()
//       assert(iter.hasNext == false)
//       // Return an iterator that looks up the hash map to find matching 
//       // vertices for each edge.
//       new EdgeTripletIterator(vidToIndex, vertexArray, edgePartition)
//     }
//     ClosureCleaner.clean(iterFun) 
//     localVidMap.zipJoin(vTableReplicatedValues).zipJoinRDD(eTable)
//       .mapPartitions( iterFun ) // end of map partition
//   }
// }


/**
 * A Graph RDD that supports computation on graphs.
 */
class GraphImpl[VD: ClassManifest, ED: ClassManifest] protected (
    @transient val vTable: VertexSetRDD[VD],
    @transient val vid2pid: VertexSetRDD[Array[Pid]],
    @transient val localVidMap: RDD[(Pid, VertexIdToIndexMap)],
    @transient val eTable: RDD[(Pid, EdgePartition[ED])] )
  extends Graph[VD, ED] {

//  def this() = this(null,null,null)


  /**
   * (localVidMap: VertexSetRDD[Pid, VertexIdToIndexMap]) is a version of the
   * vertex data after it is replicated. Within each partition, it holds a map
   * from vertex ID to the index where that vertex's attribute is stored. This
   * index refers to an array in the same partition in vTableReplicatedValues.
   *
   * (vTableReplicatedValues: VertexSetRDD[Pid, Array[VD]]) holds the vertex data
   * and is arranged as described above.
   */
  @transient val vTableReplicatedValues: RDD[(Pid, Array[VD])] =
    createVTableReplicated(vTable, vid2pid, localVidMap)


  /** Return a RDD of vertices. */
  @transient override val vertices = vTable


  /** Return a RDD of edges. */
  @transient override val edges: RDD[Edge[ED]] = {
    eTable.mapPartitions { iter => iter.next()._2.iterator }
  }


  /** Return a RDD that brings edges with its source and destination vertices together. */
  @transient override val triplets: RDD[EdgeTriplet[VD, ED]] =
    EdgeTripletBuilder.makeTriplets(localVidMap, vTableReplicatedValues, eTable)


  // {
  //   val iterFun = (iter: Iterator[(Pid, (VertexHashMap[VD], EdgePartition[ED]))]) => {
  //     val (pid, (vmap, edgePartition)) = iter.next()
  //     //assert(iter.hasNext == false)
  //     // Return an iterator that looks up the hash map to find matching 
  //     // vertices for each edge.
  //     new EdgeTripletIterator(vmap, edgePartition)
  //   }
  //   ClosureCleaner.clean(iterFun) 
  //   vTableReplicated.join(eTable).mapPartitions( iterFun ) // end of map partition
  // }




  override def cache(): Graph[VD, ED] = {
    eTable.cache()
    vid2pid.cache()
    vTable.cache()
    this
  }


  override def statistics: Map[String, Any] = {
    val numVertices = this.numVertices
    val numEdges = this.numEdges
    val replicationRatio = 
      vid2pid.map(kv => kv._2.size).sum / vTable.count
    val loadArray = 
      eTable.map{ case (pid, epart) => epart.data.size }.collect.map(x => x.toDouble / numEdges)
    val minLoad = loadArray.min
    val maxLoad = loadArray.max
    Map(
      "Num Vertices" -> numVertices, "Num Edges" -> numEdges,
      "Replication" -> replicationRatio, "Load Array" -> loadArray, 
      "Min Load" -> minLoad, "Max Load" -> maxLoad) 
  }


  override def reverse: Graph[VD, ED] = {
    val newEtable = eTable.mapPartitions( _.map{ case (pid, epart) => (pid, epart.reverse) }, 
      preservesPartitioning = true)
    new GraphImpl(vTable, vid2pid, localVidMap, newEtable)
  }


  override def mapVertices[VD2: ClassManifest](f: (Vid, VD) => VD2): Graph[VD2, ED] = {
    val newVTable = vTable.mapValuesWithKeys((vid, data) => f(vid, data))
    new GraphImpl(newVTable, vid2pid, localVidMap, eTable)
  }

  override def mapEdges[ED2: ClassManifest](f: Edge[ED] => ED2): Graph[VD, ED2] = {
    val newETable = eTable.mapPartitions(_.map{ case (pid, epart) => (pid, epart.map(f)) },
      preservesPartitioning = true)
    new GraphImpl(vTable, vid2pid, localVidMap, newETable)
  }


  override def mapTriplets[ED2: ClassManifest](f: EdgeTriplet[VD, ED] => ED2):
    Graph[VD, ED2] = {
    val newETable = eTable.zipPartitions(localVidMap, vTableReplicatedValues){ 
      (edgePartitionIter, vidToIndexIter, vertexArrayIter) =>
      val (pid, edgePartition) = edgePartitionIter.next()
      val (_, vidToIndex) = vidToIndexIter.next()
      val (_, vertexArray) = vertexArrayIter.next()
      val et = new EdgeTriplet[VD, ED]
      val newEdgePartition = edgePartition.map{e =>
        et.set(e)
        et.srcAttr = vertexArray(vidToIndex(e.srcId))
        et.dstAttr = vertexArray(vidToIndex(e.dstId))
        f(et)
      }
      Iterator((pid, newEdgePartition))
    }
    new GraphImpl(vTable, vid2pid, localVidMap, newETable)
  }

  // override def correctEdges(): Graph[VD, ED] = {
  //   val sc = vertices.context
  //   val vset = sc.broadcast(vertices.map(_.id).collect().toSet)
  //   val newEdges = edges.filter(e => vset.value.contains(e.src) && vset.value.contains(e.dst))
  //   Graph(vertices, newEdges)
  // }


  override def subgraph(epred: EdgeTriplet[VD,ED] => Boolean = (x => true), 
    vpred: (Vid, VD) => Boolean = ((a,b) => true) ): Graph[VD, ED] = {

    /** @todo The following code behaves deterministically on each
     * vertex predicate but uses additional space.  Should we swithc to
     * this version
     */
    // val predGraph = mapVertices(v => (v.data, vpred(v)))
    // val newETable = predGraph.triplets.filter(t => 
    //   if(v.src.data._2 && v.dst.data._2) {
    //     val src = Vertex(t.src.id, t.src.data._1)
    //     val dst = Vertex(t.dst.id, t.dst.data._1)
    //     epred(new EdgeTriplet[VD, ED](src, dst, t.data))
    //   } else { false })

    // val newVTable = predGraph.vertices.filter(v => v.data._1)
    //   .map(v => (v.id, v.data._1)).indexed()

    // Reuse the partitioner (but not the index) from this graph
    val newVTable = 
      VertexSetRDD(vertices.filter(v => vpred(v._1, v._2)).partitionBy(vTable.index.partitioner))


    // Restrict the set of edges to those that satisfy the vertex and the edge predicate.
    val newETable = createETable(
      triplets.filter(
        t => vpred( t.srcId, t.srcAttr ) && vpred( t.dstId, t.dstAttr ) && epred(t)
        )
        .map( t => Edge(t.srcId, t.dstId, t.attr) ))

    // Construct the Vid2Pid map. Here we assume that the filter operation 
    // behaves deterministically.  
    // @todo reindex the vertex and edge tables 
    val newVid2Pid = createVid2Pid(newETable, newVTable.index)
    val newVidMap = createLocalVidMap(newETable)

    new GraphImpl(newVTable, newVid2Pid, localVidMap, newETable)
  }


  override def groupEdgeTriplets[ED2: ClassManifest](
    f: Iterator[EdgeTriplet[VD,ED]] => ED2 ): Graph[VD,ED2] = {
      val newEdges: RDD[Edge[ED2]] = triplets.mapPartitions { partIter =>
        partIter
        // TODO(crankshaw) toList requires that the entire edge partition
        // can fit in memory right now.
        .toList
        // groups all ETs in this partition that have the same src and dst
        // Because all ETs with the same src and dst will live on the same
        // partition due to the EdgePartitioner, this guarantees that these
        // ET groups will be complete.
        .groupBy { t: EdgeTriplet[VD, ED] =>  (t.srcId, t.dstId) }
        .mapValues { ts: List[EdgeTriplet[VD, ED]] => f(ts.toIterator) }
        .toList
        .toIterator
        .map { case ((src, dst), data) => Edge(src, dst, data) }
        .toIterator
      }

      //TODO(crankshaw) eliminate the need to call createETable
      val newETable = createETable(newEdges)
      new GraphImpl(vTable, vid2pid, localVidMap, newETable)
  }


  override def groupEdges[ED2: ClassManifest](f: Iterator[Edge[ED]] => ED2 ):
    Graph[VD,ED2] = {

      val newEdges: RDD[Edge[ED2]] = edges.mapPartitions { partIter =>
        partIter.toList
        .groupBy { e: Edge[ED] => (e.srcId, e.dstId) }
        .mapValues { ts => f(ts.toIterator) }
        .toList
        .toIterator
        .map { case ((src, dst), data) => Edge(src, dst, data) }
      }
      // TODO(crankshaw) eliminate the need to call createETable
      val newETable = createETable(newEdges)

      new GraphImpl(vTable, vid2pid, localVidMap, newETable)
  }



  //////////////////////////////////////////////////////////////////////////////////////////////////
  // Lower level transformation methods
  //////////////////////////////////////////////////////////////////////////////////////////////////

  override def mapReduceTriplets[A: ClassManifest](
      mapFunc: EdgeTriplet[VD, ED] => Array[(Vid, A)],
      reduceFunc: (A, A) => A)
    : VertexSetRDD[A] = {

    ClosureCleaner.clean(mapFunc)
    ClosureCleaner.clean(reduceFunc)

    // Map and preaggregate 
    val preAgg = eTable.zipPartitions(localVidMap, vTableReplicatedValues){ 
      (edgePartitionIter, vidToIndexIter, vertexArrayIter) =>
      val (pid, edgePartition) = edgePartitionIter.next()
      val (_, vidToIndex) = vidToIndexIter.next()
      val (_, vertexArray) = vertexArrayIter.next()
      // We can reuse the vidToIndex map for aggregation here as well.
      /** @todo Since this has the downside of not allowing "messages" to arbitrary
       * vertices we should consider just using a fresh map.
       */
      val msgArray = new Array[A](vertexArray.size)
      val msgBS = new BitSet(vertexArray.size)
      // Iterate over the partition
      val et = new EdgeTriplet[VD, ED]
      edgePartition.foreach{e => 
        et.set(e)
        et.srcAttr = vertexArray(vidToIndex(e.srcId))
        et.dstAttr = vertexArray(vidToIndex(e.dstId))
        mapFunc(et).foreach{ case (vid, msg) =>
          // verify that the vid is valid
          assert(vid == et.srcId || vid == et.dstId)
          val ind = vidToIndex(vid)
          // Populate the aggregator map
          if(msgBS(ind)) {
            msgArray(ind) = reduceFunc(msgArray(ind), msg)
          } else { 
            msgArray(ind) = msg
            msgBS(ind) = true
          }
        }
      }
      // Return the aggregate map
      vidToIndex.long2IntEntrySet().fastIterator()
      // Remove the entries that did not receive a message
      .filter{ entry => msgBS(entry.getValue()) }
      // Construct the actual pairs
      .map{ entry => 
        val vid = entry.getLongKey()
        val ind = entry.getValue()
        val msg = msgArray(ind)
        (vid, msg)
      }
      }.partitionBy(vTable.index.rdd.partitioner.get)
    // do the final reduction reusing the index map
    VertexSetRDD(preAgg, vTable.index, reduceFunc)
  }


  override def outerJoinVertices[U: ClassManifest, VD2: ClassManifest]
    (updates: RDD[(Vid, U)])(updateF: (Vid, VD, Option[U]) => VD2)
    : Graph[VD2, ED] = {
    ClosureCleaner.clean(updateF)
    val newVTable = vTable.leftJoin(updates).mapValuesWithKeys(
      (vid, vu) => updateF(vid, vu._1, vu._2) )
    new GraphImpl(newVTable, vid2pid, localVidMap, eTable)
  }


} // end of class GraphImpl
















object GraphImpl {

  def apply[VD: ClassManifest, ED: ClassManifest](
    vertices: RDD[(Vid, VD)], edges: RDD[Edge[ED]],
    defaultVertexAttr: VD): 
  GraphImpl[VD,ED] = {
    apply(vertices, edges, defaultVertexAttr, (a:VD, b:VD) => a)
  }


  def apply[VD: ClassManifest, ED: ClassManifest](
    vertices: RDD[(Vid, VD)], 
    edges: RDD[Edge[ED]],
    defaultVertexAttr: VD,
    mergeFunc: (VD, VD) => VD): GraphImpl[VD,ED] = {

    val vtable = VertexSetRDD(vertices, mergeFunc) 
    /** 
     * @todo Verify that there are no edges that contain vertices 
     * that are not in vTable.  This should probably be resolved:
     *
     *  edges.flatMap{ e => Array((e.srcId, null), (e.dstId, null)) }
     *       .cogroup(vertices).map{
     *         case (vid, _, attr) => 
     *           if (attr.isEmpty) (vid, defaultValue)
     *           else (vid, attr)
     *        }
     * 
     */
    val etable = createETable(edges)
    val vid2pid = createVid2Pid(etable, vtable.index)
    val localVidMap = createLocalVidMap(etable)
    new GraphImpl(vtable, vid2pid, localVidMap, etable)
  }



  /**
   * Create the edge table RDD, which is much more efficient for Java heap storage than the
   * normal edges data structure (RDD[(Vid, Vid, ED)]).
   *
   * The edge table contains multiple partitions, and each partition contains only one RDD
   * key-value pair: the key is the partition id, and the value is an EdgePartition object
   * containing all the edges in a partition.
   */
  protected def createETable[ED: ClassManifest](edges: RDD[Edge[ED]])
    : RDD[(Pid, EdgePartition[ED])] = {
    // Get the number of partitions
    val numPartitions = edges.partitions.size
    val ceilSqrt: Pid = math.ceil(math.sqrt(numPartitions)).toInt 
    edges.map { e =>
      // Random partitioning based on the source vertex id.
      // val part: Pid = edgePartitionFunction1D(e.srcId, e.dstId, numPartitions)
      // val part: Pid = edgePartitionFunction2D(e.srcId, e.dstId, numPartitions, ceilSqrt)
      val part: Pid = randomVertexCut(e.srcId, e.dstId, numPartitions)
      //val part: Pid = canonicalEdgePartitionFunction2D(e.srcId, e.dstId, numPartitions, ceilSqrt)

      // Should we be using 3-tuple or an optimized class
      MessageToPartition(part, (e.srcId, e.dstId, e.attr))
    }
    .partitionBy(new HashPartitioner(numPartitions))
    .mapPartitionsWithIndex( (pid, iter) => {
      val builder = new EdgePartitionBuilder[ED]
      iter.foreach { message =>
        val data = message.data
        builder.add(data._1, data._2, data._3)
      }
      val edgePartition = builder.toEdgePartition
      Iterator((pid, edgePartition))
    }, preservesPartitioning = true).cache()
  }


  protected def createVid2Pid[ED: ClassManifest](
    eTable: RDD[(Pid, EdgePartition[ED])],
    vTableIndex: VertexSetIndex): VertexSetRDD[Array[Pid]] = {
    val preAgg = eTable.mapPartitions { iter =>
      val (pid, edgePartition) = iter.next()
      val vSet = new VertexSet
      edgePartition.foreach(e => {vSet.add(e.srcId); vSet.add(e.dstId)})
      vSet.iterator.map { vid => (vid.toLong, pid) }
    }
    VertexSetRDD[Pid, ArrayBuffer[Pid]](preAgg, vTableIndex, 
      (p: Pid) => ArrayBuffer(p),
      (ab: ArrayBuffer[Pid], p:Pid) => {ab.append(p); ab},
      (a: ArrayBuffer[Pid], b: ArrayBuffer[Pid]) => a ++ b)
      .mapValues(a => a.toArray).cache()
  }


  protected def createLocalVidMap[ED: ClassManifest](eTable: RDD[(Pid, EdgePartition[ED])]): 
    RDD[(Pid, VertexIdToIndexMap)] = {
    eTable.mapPartitions( _.map{ case (pid, epart) =>
      val vidToIndex = new VertexIdToIndexMap
      var i = 0
      epart.foreach{ e => 
        if(!vidToIndex.contains(e.srcId)) {
          vidToIndex.put(e.srcId, i)
          i += 1
        }
        if(!vidToIndex.contains(e.dstId)) {
          vidToIndex.put(e.dstId, i)
          i += 1
        }
      }
      (pid, vidToIndex)
    }, preservesPartitioning = true).cache()
  }


  protected def createVTableReplicated[VD: ClassManifest](
      vTable: VertexSetRDD[VD], 
      vid2pid: VertexSetRDD[Array[Pid]],
      replicationMap: RDD[(Pid, VertexIdToIndexMap)]): 
    RDD[(Pid, Array[VD])] = {
    // Join vid2pid and vTable, generate a shuffle dependency on the joined 
    // result, and get the shuffle id so we can use it on the slave.
    val msgsByPartition = vTable.zipJoin(vid2pid)
      .flatMap { case (vid, (vdata, pids)) =>
        pids.iterator.map { pid => MessageToPartition(pid, (vid, vdata)) }
      }
      .partitionBy(replicationMap.partitioner.get).cache()
   
    replicationMap.zipPartitions(msgsByPartition){ 
      (mapIter, msgsIter) =>
      val (pid, vidToIndex) = mapIter.next()
      assert(!mapIter.hasNext)
      // Populate the vertex array using the vidToIndex map
      val vertexArray = new Array[VD](vidToIndex.size)
      for (msg <- msgsIter) {
        val ind = vidToIndex(msg.data._1)
        vertexArray(ind) = msg.data._2
      }
      Iterator((pid, vertexArray))
    }.cache()

    // @todo assert edge table has partitioner

    // val localVidMap: VertexSetRDD[Pid, VertexIdToIndexMap] =
    //   msgsByPartition.mapPartitionsWithIndex( (pid, iter) => {
    //     val vidToIndex = new VertexIdToIndexMap
    //     var i = 0
    //     for (msg <- iter) {
    //       vidToIndex.put(msg.data._1, i)
    //       i += 1
    //     }
    //     Array((pid, vidToIndex)).iterator
    //   }, preservesPartitioning = true).indexed(eTable.index)

    // val vTableReplicatedValues: VertexSetRDD[Pid, Array[VD]] =
    //   msgsByPartition.mapPartitionsWithIndex( (pid, iter) => {
    //     val vertexArray = ArrayBuilder.make[VD]
    //     for (msg <- iter) {
    //       vertexArray += msg.data._2
    //     }
    //     Array((pid, vertexArray.result)).iterator
    //   }, preservesPartitioning = true).indexed(eTable.index)

    // (localVidMap, vTableReplicatedValues)
  }


  protected def edgePartitionFunction1D(src: Vid, dst: Vid, numParts: Pid): Pid = {
    val mixingPrime: Vid = 1125899906842597L 
    (math.abs(src) * mixingPrime).toInt % numParts
  }



  /**
   * This function implements a classic 2D-Partitioning of a sparse matrix.  
   * Suppose we have a graph with 11 vertices that we want to partition 
   * over 9 machines.  We can use the following sparse matrix representation:
   *
   *       __________________________________
   *  v0   | P0 *     | P1       | P2    *  |      
   *  v1   |  ****    |  *       |          |
   *  v2   |  ******* |      **  |  ****    |
   *  v3   |  *****   |  *  *    |       *  |   
   *       ----------------------------------
   *  v4   | P3 *     | P4 ***   | P5 **  * |      
   *  v5   |  *  *    |  *       |          |
   *  v6   |       *  |      **  |  ****    |
   *  v7   |  * * *   |  *  *    |       *  |   
   *       ----------------------------------
   *  v8   | P6   *   | P7    *  | P8  *   *|      
   *  v9   |     *    |  *    *  |          |
   *  v10  |       *  |      **  |  *  *    |
   *  v11  | * <-E    |  ***     |       ** |   
   *       ----------------------------------
   *
   * The edge denoted by E connects v11 with v1 and is assigned to 
   * processor P6.  To get the processor number we divide the matrix
   * into sqrt(numProc) by sqrt(numProc) blocks.  Notice that edges
   * adjacent to v11 can only be in the first colum of 
   * blocks (P0, P3, P6) or the last row of blocks (P6, P7, P8).  
   * As a consequence we can guarantee that v11 will need to be 
   * replicated to at most 2 * sqrt(numProc) machines.
   *
   * Notice that P0 has many edges and as a consequence this 
   * partitioning would lead to poor work balance.  To improve
   * balance we first multiply each vertex id by a large prime 
   * to effectively shuffle the vertex locations. 
   *
   * One of the limitations of this approach is that the number of
   * machines must either be a perfect square.  We partially address
   * this limitation by computing the machine assignment to the next 
   * largest perfect square and then mapping back down to the actual 
   * number of machines.  Unfortunately, this can also lead to work 
   * imbalance and so it is suggested that a perfect square is used. 
   *   
   *
   */
  protected def edgePartitionFunction2D(src: Vid, dst: Vid, 
    numParts: Pid, ceilSqrtNumParts: Pid): Pid = {
    val mixingPrime: Vid = 1125899906842597L 
    val col: Pid = ((math.abs(src) * mixingPrime) % ceilSqrtNumParts).toInt
    val row: Pid = ((math.abs(dst) * mixingPrime) % ceilSqrtNumParts).toInt
    (col * ceilSqrtNumParts + row) % numParts
  }


  /**
   * Assign edges to an aribtrary machine corresponding to a 
   * random vertex cut.
   */
  protected def randomVertexCut(src: Vid, dst: Vid, numParts: Pid): Pid = {
    math.abs((src, dst).hashCode()) % numParts
  }


  /**
   * @todo This will only partition edges to the upper diagonal
   * of the 2D processor space.
   */
  protected def canonicalEdgePartitionFunction2D(srcOrig: Vid, dstOrig: Vid, 
    numParts: Pid, ceilSqrtNumParts: Pid): Pid = {
    val mixingPrime: Vid = 1125899906842597L 
    // Partitions by canonical edge direction
    val src = math.min(srcOrig, dstOrig)
    val dst = math.max(srcOrig, dstOrig)
    val col: Pid = ((math.abs(src) * mixingPrime) % ceilSqrtNumParts).toInt
    val row: Pid = ((math.abs(dst) * mixingPrime) % ceilSqrtNumParts).toInt
    (col * ceilSqrtNumParts + row) % numParts
  }

} // end of object GraphImpl

