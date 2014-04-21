package mllib.qa

class TicToc(name: String = "") {

  var start = System.nanoTime()

  def tic() {
    start = System.nanoTime()
  }

  def toc(): Double = {
    val time = (System.nanoTime() - start) / 1e9
    println(s"$name took $time seconds.")
    time
  }
}
