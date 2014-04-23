package mllib.qa

import scala.pickling._
import scala.pickling.json._

import scopt.OptionParser
import com.esotericsoftware.kryo.{Serializer, Kryo}
import org.apache.log4j.{Level, Logger}

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.recommendation.{ALS, MatrixFactorizationModel, Rating}
import org.apache.spark.rdd.RDD
import org.apache.spark.serializer.{KryoSerializer, KryoRegistrator}
import org.jblas.DoubleMatrix
import com.esotericsoftware.kryo.io.{Input, Output}
import org.apache.spark.broadcast.Broadcast

case class ALSConfig(
    kryo: Boolean = false,
    input: String = null,
    numPartitions: Int = 2,
    rank: Int = 10)

class ALSRegistrator extends KryoRegistrator {
  override def registerClasses(kryo: Kryo) {
    kryo.register(classOf[Rating]) // , new Serializer[Rating]() {
//      override def read(kryo: Kryo, input: Input, `type`: Class[Rating]): Rating = {
//        Rating(input.readInt(), input.readInt(), input.readDouble())
//      }
//      override def write(kryo: Kryo, output: Output, `object`: Rating): Unit = {
//        output.writeInt(`object`.user)
//        output.writeInt(`object`.product)
//        output.writeDouble(`object`.rating)
//      }
//    })

    kryo.register(classOf[DoubleMatrix])
//    kryo.register(classOf[DoubleMatrix], new Serializer[DoubleMatrix]() {
//
//      override def read(kryo: Kryo, input: Input, `type`: Class[DoubleMatrix]): DoubleMatrix = {
//        val m = input.readInt()
//        val n = input.readInt()
//        val mat = new DoubleMatrix(m, n)
//        val len = m * n
//        val data = mat.data
//        var i = 0
//        while (i < len) {
//          data(i) = input.readDouble()
//          i += 1
//        }
//        mat
//      }
//
//      override def write(kryo: Kryo, output: Output, `object`: DoubleMatrix): Unit = {
//        val m = `object`.rows
//        val n = `object`.columns
//        val data = `object`.data
//        output.writeInt(m)
//        output.writeInt(n)
//        data.foreach { x =>
//          output.writeDouble(x)
//        }
//      }
//    })
  }
}

/*
 Commands to submit:

 ../spark/bin/spark-submit target/scala-2.10/mllib-qa-assembly-0.1.jar --class mllib.qa.MovieLensALS
   --master spark://ec2-54-221-139-69.compute-1.amazonaws.com:7077 --executor-memory 5g --num-executors 3
   --arg --input --arg hdfs://ec2-54-221-139-69.compute-1.amazonaws.com:9000/tmp/ratings.dat
   --arg --numPartitions --arg 8


 */
object MovieLensALS extends App {

  object TrainingMode extends Enumeration {
    type TrainingMode = Value
    val StaticImplicit, Static, Builder, BuilderImplicit = Value
  }

  import TrainingMode._

  val parser = new OptionParser[ALSConfig]("MovieLensALS") {
    opt[Int]("numPartitions").action {
      (x, c) =>
        c.copy(numPartitions = x)
    }.text("number partitions")
    opt[Int]("rank").action {
      (x, c) =>
        c.copy(rank = x)
    }.text("rank")
    opt[Boolean]("kryo").action {
      (x, c) =>
        c.copy(kryo = x)
    }
    arg[String]("input").action {
      (x, c) =>
        c.copy(input = x)
    }
  }

  parser.parse(args, ALSConfig()).map {
    config =>
      println("Config: " + config.pickle) // TODO: use json pickle

      val conf = new SparkConf().setAppName("MovieLensALS")
      if (config.kryo) {
        conf.set("spark.serializer", classOf[KryoSerializer].getName)
          .set("spark.kryo.registrator", classOf[ALSRegistrator].getName)
          // .set("spark.kryo.referenceTracking", "false")
          .set("spark.kryoserializer.buffer.mb", "32")
          // .set("spark.locality.wait", "10000")
      }
      val sc = new SparkContext(conf)

      Logger.getRootLogger.setLevel(Level.WARN)

      val ratings = sc.textFile(config.input).map {
        line =>
          val fields = line.split("::")
          Rating(fields(0).toInt, fields(1).toInt, fields(2).toDouble)
      }.cache()

      val numRatings = ratings.count()
      val numUsers = ratings.map(_.user).distinct().count()
      val numMovies = ratings.map(_.product).distinct().count()

      println(s"Got $numRatings ratings from $numUsers users on $numMovies movies.")

      val splits = ratings.randomSplit(Array(0.8, 0.2))

      val training = splits(0).repartition(config.numPartitions) cache()
      val test = splits(1).repartition(config.numPartitions).cache()

      val numTraining = training.count()
      val numTest = test.count()
      println(s"Training: $numTraining, test: $numTest.")

      for (mode <- TrainingMode.values) {
        val timer = new TicToc(mode.toString)
        timer.tic()
        val model = train(mode, config)(training)
        timer.toc()
        val rmse = computeRmse(model, test, numTest)
        println(s"RMSE of $mode is $rmse.")
      }

      sc.stop()

  } getOrElse {

    System.exit(1)
  }

  def train(mode: TrainingMode, config: ALSConfig): RDD[Rating] => MatrixFactorizationModel = {
    mode match {
    case Static =>
      (training: RDD[Rating]) =>
        ALS.train(training, config.rank, 20, 1.0)
    case Builder =>
      (training: RDD[Rating]) =>
        new ALS()
            .setRank(config.rank)
            .setIterations(20)
            .setLambda(1.0)
            .run(training)
    case StaticImplicit =>
      (training: RDD[Rating]) =>
        ALS.trainImplicit(training, config.rank, 20, 1.0, 1.0)
    case BuilderImplicit =>
      (training: RDD[Rating]) =>
        new ALS()
            .setRank(config.rank)
            .setIterations(20)
            .setLambda(1.0)
            .setImplicitPrefs(true)
            .setAlpha(1.0)
            .run(training)
    }
  }

  /** Compute RMSE (Root Mean Squared Error). */
  def computeRmse(model: MatrixFactorizationModel, data: RDD[Rating], n: Long) = {
    val predictions: RDD[Rating] = model.predict(data.map(x => (x.user, x.product)))
    val predictionsAndRatings = predictions.map(x => ((x.user, x.product), x.rating))
        .join(data.map(x => ((x.user, x.product), x.rating)))
        .values
    math.sqrt(predictionsAndRatings.map(x => (x._1 - x._2) * (x._1 - x._2)).reduce(_ + _) / n)
  }
}
