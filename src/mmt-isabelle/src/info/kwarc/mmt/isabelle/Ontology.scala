package info.kwarc.mmt.isabelle

import info.kwarc.mmt.api.ContentPath
import java.net.URI


object Ontology
{
  /* relational triples */

  def unary(subject: ContentPath, predicate: String): isabelle.RDF.Triple =
    isabelle.RDF.Triple(subject.toString, predicate)

  def binary(subject: ContentPath, predicate: String, `object`: isabelle.XML.Body): isabelle.RDF.Triple =
    isabelle.RDF.Triple(subject.toString, predicate, `object` = `object`)

  def binary(subject: ContentPath, predicate: String, `object`: String): isabelle.RDF.Triple =
    binary(subject, predicate, List(isabelle.XML.Text(`object`)))

  def binary(subject: ContentPath, predicate: String, resource: ContentPath): isabelle.RDF.Triple =
    isabelle.RDF.Triple(subject.toString, predicate, resource = resource.toString)

  def binary(subject: ContentPath, predicate: String, resource: URI): isabelle.RDF.Triple =
    isabelle.RDF.Triple(subject.toString, predicate, resource = resource.toString)


  /* namespace */

  val ulo: isabelle.XML.Namespace = isabelle.XML.Namespace("ulo", "https://mathhub.info/ulo")

  def rdf_document(triples: List[isabelle.RDF.Triple]): isabelle.XML.Elem =
    isabelle.RDF.document(isabelle.RDF.triples(triples),
      namespaces = isabelle.RDF.default_namespaces ::: List(ulo))

  object ULO
  {
    /* unaries */

    val `type` = ulo("type") // type constructors
    val `object` = ulo("object") // term constants
    val statement = ulo("statement")  // fact items (thm)

    val theory = ulo("theory") // theory, locale

    val section = ulo("section") // document headings: chapter, section, ..., subparagraph

    val para = ulo("para") // theorem-like statements (e.g. "theorem", "lemma", "axiomatization")
    val definition = ulo("definition") // definitional statements (kind "thy_defn", "thy_goal_defn")

    // unused: could be derived from sessions structure etc.
    val file = ulo("file")  // could be theory file
    val folder = ulo("folder")  // could be session
    val library = ulo("library")  // could be "Distribution", "AFP", "IsaFoR" etc.
    val group = ulo("group")

    val primitive = ulo("primitive") // consts or facts from "axiomatization"
    val derived = ulo("derived") // HOL typedefs, proven statements

    // unused: this is a property of the context, not of individual facts
    val simplification_rule = ulo("simplification-rule")

    // unused: could be result of "Judgement Day" Sledgehammer exploration
    val automatically_proved = ulo("automatically_proved")

    val experimental = ulo("experimental") // unused: could be derived from 'experimental' target
    val deprecated = ulo("deprecated") // unused: could be specified via document marker


    /* binaries */

    val paratype = ulo("paratype") // name of theorem-like statements as its type (e.g. "theorem")

    val instance_of = ulo("instance-of") // locale interpretation

    val specifies = ulo("specifies") // theory/locale declares item
    val specified_in = ulo("specified-in") // inverse of "specifies"

    val uses = ulo("uses") // dependencies of term language (not proofs)

    val generated_by = ulo("generated-by")
    val inductive_on = ulo("inductive-on")
    val mutual_with = ulo("mutual-with") // TODO e.g. Spec_Rules peer group


    // TODO manual markers!?
    val same_as = ulo("same-as")
    val similar_to = ulo("similar-to")
    val alternative_for = ulo("alternative-for")
    val see_also = ulo("see-also")
    val antonym_of = ulo("antonym-of")
    val formalizes = ulo("formalizes")
    val aligned_with = ulo("aligned_with")
    val inspired_by = ulo("inspired_by")

    val source_ref = ulo("source-ref") // entity position
    val check_time = ulo("check-time") // elapsed time (ms)
    val external_size = ulo("external-size") // source size (UTF-8 bytes)
    val internal_size = ulo("internal-size") // unused
  }
}