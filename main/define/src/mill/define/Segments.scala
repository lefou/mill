package mill.define

import scala.math.Ordering.Implicits.seqOrdering

/**
 * Models a path with the Mill build hierarchy, e.g. `amm.util[2.11].test.compile`.
 * Segments must start with a [[Segment.Label]].
 *
 * `.`-separated segments are [[Segment.Label]]s,
 * while `[]`-delimited segments are [[Segment.Cross]]s
 */
case class Segments private (value: Seq[Segment]) {

  def init: Segments = Segments(value.init)
  def ++(other: Segment): Segments = Segments(value ++ Seq(other))
  def ++(other: Seq[Segment]): Segments = Segments(value ++ other)
  def ++(other: Segments): Segments = Segments(value ++ other.value)

  def startsWith(prefix: Segments): Boolean =
    value.startsWith(prefix.value)

  def last: Segment.Label = value.last match {
    case l: Segment.Label => l
    case _ =>
      throw new IllegalArgumentException("Segments must end with a Label, but found a Cross.")
  }

  def parts: List[String] = value.toList match {
    case Nil => Nil
    case Segment.Label(head) :: rest =>
      val stringSegments = rest.flatMap {
        case Segment.Label(s) => Seq(s)
        case Segment.Cross(vs) => vs
      }
      head +: stringSegments
    case Segment.Cross(_) :: _ =>
      throw new IllegalArgumentException("Segments must start with a Label, but found a Cross.")
  }

  def head: Segment.Label = value.head match {
    case l: Segment.Label => l
    case _ =>
      throw new IllegalArgumentException("Segments must start with a Label, but found a Cross.")
  }

  def render: String = value.toList match {
    case Nil => ""
    case Segment.Label(head) :: rest =>
      val stringSegments = rest.map {
        case Segment.Label(s) => "." + s
        case Segment.Cross(vs) => "[" + vs.mkString(",") + "]"
      }
      head + stringSegments.mkString
    case Segment.Cross(_) :: _ =>
      throw new IllegalArgumentException("Segments must start with a Label, but found a Cross.")
  }
  override lazy val hashCode: Int = value.hashCode()
}

object Segments {
  implicit def ordering: Ordering[Segments] = Ordering.by(_.value)
  def apply(): Segments = new Segments(Nil)
  def apply(items: Seq[Segment]): Segments = new Segments(items)
  def labels(values: String*): Segments = Segments(values.map(Segment.Label))
}
