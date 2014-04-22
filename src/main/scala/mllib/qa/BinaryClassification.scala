package mllib.qa

import org.apache.log4j.{Level, Logger}
import scopt.OptionParser

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.mllib.classification.{ClassificationModel, LogisticRegressionWithSGD, SVMWithSGD}
import org.apache.spark.mllib.evaluation.binary.BinaryClassificationMetrics
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.rdd.RDD

/*
 Commands to submit:

 ../spark/bin/spark-submit target/scala-2.10/mllib-qa-assembly-0.1.jar --class mllib.qa.BinaryClassfication
   --master spark://ec2-54-221-139-69.compute-1.amazonaws.com:7077 --executor-memory 5g --num-executors 3
   --arg --input --arg hdfs://ec2-54-221-139-69.compute-1.amazonaws.com:9000/tmp/rcv1.binary.txt

 */
object BinaryClassification extends App {

  object Solver extends Enumeration {
    type Solver = Value
    val SVM, LR = Value
  }

  object Format extends Enumeration {
    type Format = Value
    val Sparse, Dense = Value
  }

  import Format._
  import Solver._

  case class BinaryClassificationConfig(
      input: String = null,
      numIterations: Int = 100,
      numPartitions: Int = 12,
      format: Format = Sparse,
      lambda: Double = 0.1)

  val parser = new OptionParser[BinaryClassificationConfig]("BinaryClassification") {
    opt[Int]("numIterations").action {
      (x, c) =>
        c.copy(numIterations = x)
    }.text("number iterations")
    opt[String]("format").action {
      (x, c) =>
        c.copy(format = Format.withName(x))
    }.text("format: " + Format.values)
    opt[Int]("numPartitions").action {
      (x, c) =>
        c.copy(numPartitions = x)
    }.text("number partitions")
    arg[String]("input").action {
      (x, c) =>
        c.copy(input = x)
    }
  }

  parser.parse(args, BinaryClassificationConfig()).map {
    config =>
      println("Config: " + config) // TODO: use json pickle

      val conf = new SparkConf()
        .setAppName("BinaryClassification")
      val sc = new SparkContext(conf)

      Logger.getRootLogger.setLevel(Level.WARN)
      Logger.getLogger("com.github.fommil.netlib").setLevel(Level.ALL)
      Logger.getLogger("com.github.fommil.jniloader").setLevel(Level.ALL)

      val examples = MLUtils.loadLibSVMData(sc, config.input).cache()
      val numExamples = examples.count()

      println(s"Loaded $numExamples examples.")

      val splits = examples.randomSplit(Array(0.8, 0.2))

      val training = splits(0).repartition(config.numPartitions) cache()
      val test = splits(1).repartition(config.numPartitions).cache()

      val numTraining = training.count()
      val numTest = test.count()
      println(s"Training: $numTraining, test: $numTest.")

      for (solver <- Solver.values) {
        val timer = new TicToc(solver.toString)
        timer.tic()
        val model = train(solver, config)(training)
        timer.toc()
        val auc = computeAUC(model, test)
        println(s"AreaUnderROC of $solver is $auc.")
      }

      sc.stop()

  } getOrElse {

    System.exit(1)
  }

  def train(solver: Solver, config: BinaryClassificationConfig): RDD[LabeledPoint] => ClassificationModel = {
    solver match {
      case SVM =>
        (training: RDD[LabeledPoint]) =>
          SVMWithSGD.train(training, config.numIterations).clearThreshold()
      case LR =>
        (training: RDD[LabeledPoint]) =>
          LogisticRegressionWithSGD.train(training, config.numIterations).clearThreshold()
    }
  }

  def computeAUC(model: ClassificationModel, test: RDD[LabeledPoint]): Double = {
    val predictions = model.predict(test.map(_.features))
    val metrics = new BinaryClassificationMetrics(predictions.zip(test.map(_.label)))
    metrics.areaUnderROC()
  }
}
