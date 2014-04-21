package mllib.qa

import scopt.OptionParser

import org.apache.spark.{SparkContext, SparkConf}
import org.apache.spark.mllib.recommendation.{MatrixFactorizationModel, ALS, Rating}
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext._
import org.apache.spark.serializer.{KryoSerializer, KryoRegistrator}
import com.esotericsoftware.kryo.Kryo
import org.apache.log4j.{Level, Logger}

case class ALSConfig(
    master: String = "local[2]",
    input: String = null,
    numPartitions: Int = 2,
    rank: Int = 10)

private class ALSRegistrator extends KryoRegistrator {
  override def registerClasses(kryo: Kryo) {
    kryo.register(classOf[Rating])
  }
}

object MovieLensALS extends App {

  object TrainingMode extends Enumeration {
    type TrainingMode = Value
    val Static, StaticImplicit, Builder, BuilderImplicit = Value
  }

  import TrainingMode._

  val parser = new OptionParser[ALSConfig]("MovieLensALS") {
    opt[String]("master").action {
      (x, c) =>
        c.copy(master = x)
    }.text("Spark master")
    opt[String]("input").action {
      (x, c) =>
        c.copy(input = x)
    }.text("input path").required()
    opt[Int]("numPartitions").action {
      (x, c) =>
        c.copy(numPartitions = x)
    }.text("number partitions")
  }

  parser.parse(args, ALSConfig()).map {
    config =>
      println("Config: " + config) // TODO: use json pickle

      Logger.getRootLogger.setLevel(Level.WARN)

      val conf = new SparkConf()
          .setMaster(config.master)
          .setAppName("MovelensALS")
          .setJars(SparkContext.jarOfClass(this.getClass))
          .set("spark.serializer", classOf[KryoSerializer].getName)
          .set("spark.kryo.registrator", classOf[ALSRegistrator].getName)
          .set("spark.kryo.referenceTracking", "false")
          .set("spark.kryoserializer.buffer.mb", "8")
          .set("spark.locality.wait", "10000")
      val sc = new SparkContext(conf)

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
        val model = train(mode)(training)
        timer.toc()
        val rmse = computeRmse(model, test, numTest)
        println(s"Static explicit RMSE is $rmse.")
      }

      sc.stop()

  } getOrElse {

    System.exit(1)
  }

  def train(mode: TrainingMode): RDD[Rating] => MatrixFactorizationModel = {
    mode match {
    case Static =>
      (training: RDD[Rating]) =>
        ALS.train(training, 10, 20, 1.0)
    case Builder =>
      (training: RDD[Rating]) =>
        new ALS()
            .setRank(10)
            .setIterations(20)
            .setLambda(1.0)
            .run(training)
    case StaticImplicit =>
      (training: RDD[Rating]) =>
        ALS.trainImplicit(training, 10, 20, 1.0, 1.0)
    case BuilderImplicit =>
      (training: RDD[Rating]) =>
        new ALS()
            .setRank(10)
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
