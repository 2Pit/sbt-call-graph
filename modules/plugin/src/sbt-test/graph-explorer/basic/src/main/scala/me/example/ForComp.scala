package me.example

object ForComp {

  /** Uses for-comprehension — flatMap/map are synthetic, but step1/step2 calls
   *  must appear in the call graph as edges from `run`. */
  def run(): Option[Int] =
    for {
      a <- step1()
      b <- step2(a)
    } yield a + b

  def step1(): Option[Int] = Some(1)
  def step2(x: Int): Option[Int] = Some(x + 1)

  /** Explicit .flatMap — same result as for-comprehension, edges must be captured. */
  def runExplicit(): Option[Int] =
    step1().flatMap(a => step2(a))
}
