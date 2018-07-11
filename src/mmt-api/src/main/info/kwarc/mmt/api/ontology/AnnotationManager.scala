package info.kwarc.mmt.api.ontology

import info.kwarc.mmt.api._
import frontend._

/** provides additional information about MMT elements (e.g., compilation warnings, refactoring suggestions, discussion pointers) */
abstract class AnnotationProvider extends Extension {
  /** this must be fast enough to be called by jEdit every time a line is repainted */
  def apply(p: Path): List[Annotation]
}

/** an annotation returned by an [[AnnotationProvider]], to be used by e.g. [[MMTGutterAnnotations]] */
abstract class Annotation {
  /** the tooltip for this annotation */
  def getTooltip: String
  def getMarker: Char = 'A'

  override def toString: String = getTooltip
}