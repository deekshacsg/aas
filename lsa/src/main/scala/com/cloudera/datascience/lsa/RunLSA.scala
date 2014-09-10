/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloudera.datascience.lsa

import com.cloudera.datascience.lsa.ParseWikipedia._

import org.apache.spark.mllib.linalg.distributed.RowMatrix
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkContext, SparkConf}
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.linalg._

import scala.collection.Map
import scala.collection.mutable.ArrayBuffer
import breeze.linalg.{DenseMatrix => BreezeDenseMatrix, DenseVector => BreezeDenseVector,
  SparseVector => BreezeSparseVector}
import org.apache.spark.mllib.linalg.SingularValueDecomposition

object RunLSA {
  def main(args: Array[String]) {
    val k = if (args.length > 0) args(0).toInt else 100
    val numTerms = if (args.length > 1) args(1).toInt else 50000
    val sampleSize = if (args.length > 2) args(2).toDouble else 0.1

    val conf = new SparkConf().setAppName("Wiki LSA")
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    val sc = new SparkContext(conf)

    val (termDocMatrix, termIds, docIds, idfs) = preprocessing(sampleSize, numTerms, sc)
    termDocMatrix.cache()
    //println("termDocMatrix num rows: " + termDocMatrix.count())
    val mat = new RowMatrix(termDocMatrix)
    val svd = mat.computeSVD(k, computeU=true)

    println("Singular values: " + svd.s)
    val topTermsTopConcepts = topTermsInTopConcepts(svd, 20, 15, termIds)
    for (concept <- topTermsTopConcepts) {
      println("Concept: " + concept.mkString(","))
      println()
    }

    val topDocsTopConcepts = topDocsInTopConcepts(svd, 20, 15, docIds)
    for (concept <- topDocsTopConcepts) {
      println("Concept: " + concept.mkString(","))
      println()
    }
  }

  /**
   * Returns an RDD of rows of the term document matrix, a mapping of column indices to terms, and a
   * mapping of row IDs to document titles.
   */
  def preprocessing(sampleSize: Double, numTerms: Int, sc: SparkContext)
      : (RDD[Vector], Map[Int, String], Map[Long, String], Map[String, Double]) = {
    val pages = readFile("/user/srowen/DataSets/Wikipedia/20131205/", sc)
      .sample(false, sampleSize, 11L)

    val plainText = pages.filter(_ != null).flatMap(wikiXmlToPlainText)

    val stopWords = sc.broadcast(loadStopWords("stopwords.txt")).value

    val lemmatized = plainText.mapPartitions(iter => {
      val pipeline = createNLPPipeline()
      iter.map{case(title, contents) => (title, plainTextToLemmas(contents, stopWords, pipeline))}
    })

    val filtered = lemmatized.filter(_._2.size > 1)

    termDocumentMatrix(filtered, stopWords, numTerms, sc)
  }

  def topTermsInTopConcepts(svd: SingularValueDecomposition[RowMatrix, Matrix], numConcepts: Int,
      numTerms: Int, termIds: Map[Int, String]): Seq[Seq[(String, Double)]] = {
    // TODO: can we make it easier to actually look at the insides of matrices
    val v = svd.V
    val topTerms = new ArrayBuffer[Seq[(String, Double)]]()
    for (i <- 0 until numConcepts) {
      val offs = i * v.numRows
      val termWeights = v.toArray.slice(offs, offs + v.numRows).zipWithIndex
      val sorted = termWeights.sortBy(_._1)
      topTerms += sorted.takeRight(numTerms).map{case (score, id) => (termIds(id), score)}
    }
    topTerms.map(_.reverse)
  }

  def topDocsInTopConcepts(svd: SingularValueDecomposition[RowMatrix, Matrix], numConcepts: Int,
      numDocs: Int, docIds: Map[Long, String]): Seq[Seq[(String, Double)]] = {
    val u  = svd.U
    val topDocs = new ArrayBuffer[Seq[(String, Double)]]()
    for (i <- 0 until numConcepts) {
      val docWeights = u.rows.map(_.toArray(i)).zipWithUniqueId
      topDocs += docWeights.top(numDocs).map{case (score, id) => (docIds(id), score)}
    }
    topDocs
  }

  /**
   * Selects a row from a matrix.
   */
  def row(mat: BreezeDenseMatrix[Double], index: Int): Seq[Double] = {
    (0 until mat.cols).map(c => mat(index, c))
  }

  /**
   * Selects a row from a matrix.
   */
  def row(mat: Matrix, index: Int): Seq[Double] = {
    val arr = mat.toArray
    (0 until mat.numCols).map(i => arr(index + i * mat.numRows))
  }

  /**
   * Selects a row from a distributed matrix.
   */
  def row(mat: RowMatrix, id: Long): Array[Double] = {
    mat.rows.zipWithUniqueId.map(_.swap).lookup(id).head.toArray
  }

  /**
   * Finds the product of a dense matrix and a diagonal matrix represented by a vector.
   * Breeze doesn't support efficient diagonal representations, so multiply manually.
   */
  def multiplyByDiagonalMatrix(mat: Matrix, diag: Vector): BreezeDenseMatrix[Double] = {
    val sArr = diag.toArray
    new BreezeDenseMatrix[Double](mat.numRows, mat.numCols, mat.toArray)
      .mapPairs{case ((r, c), v) => v * sArr(c)}
  }

  /**
   * Finds the product of a distributed matrix and a diagonal matrix represented by a vector.
   */
  def multiplyByDiagonalMatrix(mat: RowMatrix, diag: Vector): RowMatrix = {
    val sArr = diag.toArray
    new RowMatrix(mat.rows.map(vec => {
      val vecArr = vec.toArray
      val newArr = (0 until vec.size).toArray.map(i => vecArr(i) * sArr(i))
      Vectors.dense(newArr)
    }))
  }

  /**
   * Returns a matrix where each row is divided by its length.
   */
  def rowsNormalized(mat: BreezeDenseMatrix[Double]): BreezeDenseMatrix[Double] = {
    val newMat = new BreezeDenseMatrix[Double](mat.rows, mat.cols)
    for (r <- 0 until mat.rows) {
      val length = math.sqrt((0 until mat.cols).map(c => mat(r, c) * mat(r, c)).sum)
      (0 until mat.cols).map(c => newMat.update(r, c, mat(r, c) / length))
    }
    newMat
  }

  /**
   * Returns a distributed matrix where each row is divided by its length.
   */
  def rowsNormalized(mat: RowMatrix): RowMatrix = {
    new RowMatrix(mat.rows.map(vec => {
      val length = math.sqrt(vec.toArray.map(x => x * x).sum)
      Vectors.dense(vec.toArray.map(_ / length))
    }))
  }

  /**
   * Finds terms relevant to a term. Returns the term IDs and scores for the terms with the highest
   * relevance scores to the given term.
   */
  def topTermsForTerm(normalizedVS: BreezeDenseMatrix[Double], termId: Int): Seq[(Double, Int)] = {
    // Look up the row in VS corresponding to the given term ID.
    val termRowVec = new BreezeDenseVector[Double](row(normalizedVS, termId).toArray)

    // Compute scores against every term
    val termScores = (normalizedVS * termRowVec).toArray.zipWithIndex

    // Find the terms with the highest scores
    termScores.sortBy(-_._1).take(10)
  }

  /**
   * Finds docs relevant to a doc. Returns the doc IDs and scores for the docs with the highest
   * relevance scores to the given doc.
   */
  def topDocsForDoc(normalizedUS: RowMatrix, docId: Long): Seq[(Double, Long)] = {
    // Look up the row in US corresponding to the given doc ID.
    val docRowArr = row(normalizedUS, docId)
    val docRowVec = Matrices.dense(docRowArr.length, 1, docRowArr)

    // Compute scores against every doc
    val docScores = normalizedUS.multiply(docRowVec)

    // Find the docs with the highest scores
    val allDocWeights = docScores.rows.map(_.toArray(0)).zipWithUniqueId

    // Docs can end up with NaN score if their row in U is all zeros.  Filter these out.
    allDocWeights.filter(!_._1.isNaN).top(10)
  }

  /**
   * Finds docs relevant to a term. Returns the doc IDs and scores for the docs with the highest
   * relevance scores to the given term.
   */
  def topDocsForTerm(US: RowMatrix, V: Matrix, termId: Int): Seq[(Double, Long)] = {
    val termRowArr = row(V, termId).toArray
    val termRowVec = Matrices.dense(termRowArr.length, 1, termRowArr)

    // Compute scores against every doc
    val docScores = US.multiply(termRowVec)

    // Find the docs with the highest scores
    val allDocWeights = docScores.rows.map(_.toArray(0)).zipWithUniqueId
    allDocWeights.top(10)
  }

  def termsToQueryVector(terms: Seq[String], idTerms: Map[String, Int], idfs: Map[String, Double])
    : BreezeSparseVector[Double] = {
    val indices = terms.map(idTerms(_)).toArray
    val values = terms.map(idfs(_)).toArray
    new BreezeSparseVector[Double](indices, values, idTerms.size)
  }

  def topDocsForTermQuery(US: RowMatrix, V: Matrix, query: BreezeSparseVector[Double])
    : Seq[(Double, Long)] = {
    val breezeV = new BreezeDenseMatrix[Double](V.numRows, V.numCols, V.toArray)
    val termRowArr = (breezeV.t * query).asInstanceOf[BreezeDenseVector[Double]].toArray

    val termRowVec = Matrices.dense(termRowArr.length, 1, termRowArr)

    // Compute scores against every doc
    val docScores = US.multiply(termRowVec)

    // Find the docs with the highest scores
    val allDocWeights = docScores.rows.map(_.toArray(0)).zipWithUniqueId
    allDocWeights.top(10)
  }

  def printTopTermsForTerm(normalizedVS: BreezeDenseMatrix[Double],
      term: String, idTerms: Map[String, Int], termIds: Map[Int, String]) {
    printIdWeights(topTermsForTerm(normalizedVS, idTerms(term)), termIds)
  }

  def printTopDocsForDoc(normalizedUS: RowMatrix, doc: String, idDocs: Map[String, Long],
      docIds: Map[Long, String]) {
    printIdWeights(topDocsForDoc(normalizedUS, idDocs(doc)), docIds)
  }

  def printTopDocsForTerm(US: RowMatrix, V: Matrix, term: String, idTerms: Map[String, Int],
      docIds: Map[Long, String]) {
    printIdWeights(topDocsForTerm(US, V, idTerms(term)), docIds)
  }

  def printIdWeights[T](idWeights: Seq[(Double, T)], entityIds: Map[T, String]) {
    println(idWeights.map{case (score, id) => (entityIds(id), score)}.mkString(", "))
  }

  /*
  def docIdToConceptWeights(svd: SingularValueDecomposition[RowMatrix, Matrix], docId: Long)
      : Array[Double] = {
    svd.U.rows.zipWithUniqueId.map(_.swap).lookup(docId).head.toArray
  }

  def conceptWeightsToTopTermWeights(svd: SingularValueDecomposition[RowMatrix, Matrix],
      conceptWeights: Array[Double], numTerms: Int): Seq[(Double, Int)] = {
    val bv = new BreezeDenseVector[Double](conceptWeights)
    val bm = new BreezeDenseMatrix[Double](svd.V.numRows, svd.V.numCols, svd.V.toArray)
//    val s = new BreezeDenseVector[Double](svd.s.toArray)
    val allTermWeights = (bm * bv).toArray.zipWithIndex
    allTermWeights.sortBy(- _._1).take(numTerms)
  }

  def conceptWeightsToTopDocWeights(svd: SingularValueDecomposition[RowMatrix, Matrix],
      conceptWeights: Array[Double], numDocs: Int): Seq[(Double, Long)] = {
    val u = svd.U
    val allDocWeightsMat = u.multiply(Matrices.dense(conceptWeights.length, 1, conceptWeights))
    val allDocWeights = allDocWeightsMat.rows.map(_.toArray(0)).zipWithUniqueId
    allDocWeights.top(numDocs)
  }

  def printIdWeights[T](idWeights: Seq[(Double, T)], entityIds: Map[T, String]) {
    println(idWeights.map{case (score, id) => (entityIds(id), score)}.mkString(", "))
  }

  def printTermsRelevantToTerm(svd: SingularValueDecomposition[RowMatrix, Matrix], term: String,
      numTerms: Int, idTerms: Map[String, Int], termIds: Map[Int, String]) {
    val cweights = termIdToConceptWeights(svd, idTerms(term))
    for (i <- 0 until cweights.length) {
      cweights(i) /= (svd.s.toArray(i))
    }
    val tweights = conceptWeightsToTopTermWeights(svd, cweights, numTerms)
    printIdWeights(tweights, termIds)
  }

  def printTermsRelevantToDoc(normalizedVS: Matrix, doc: String,
      numDocs: Int, idDocs: Map[String, Long], termIds: Map[Int, String], numTerms: Int) {
    val cweights = docIdToConceptWeights(svd, idDocs(doc))
    val tweights = conceptWeightsToTopTermWeights(svd, cweights, numTerms)
    printIdWeights(tweights, termIds)
  }

  def printDocsRelevantToDoc(svd: SingularValueDecomposition[RowMatrix, Matrix], doc: String,
      numDocs: Int, idDocs: Map[String, Long], docIds: Map[Long, String]) {
    val cweights = docIdToConceptWeights(svd, idDocs(doc))
    val dweights = conceptWeightsToTopDocWeights(svd, cweights, numDocs)
    printIdWeights(dweights, docIds)
  }

  def printDocsRelevantToTerm(svd: SingularValueDecomposition[RowMatrix, Matrix], term: String,
      numDocs: Int, idTerms: Map[String, Int], docIds: Map[Long, String]) {
    val cweights = termIdToConceptWeights(svd, idTerms(term))
    for (i <- 0 until cweights.length) {
      cweights(i) /= (svd.s.toArray(i))
    }
    val dweights = conceptWeightsToTopDocWeights(svd, cweights, numDocs)
    printIdWeights(dweights, docIds)
  }
  */
}
