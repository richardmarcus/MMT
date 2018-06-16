package info.kwarc.mmt.api.ontology
import info.kwarc.mmt.api._
import info.kwarc.mmt.api.utils._
import info.kwarc.mmt.api.utils.MyList.fromList
import info.kwarc.mmt.api.objects._
import scala.collection.mutable.{HashSet,HashMap}
import info.kwarc.mmt.api.checking.ObjectChecker
import info.kwarc.mmt.api.checking.Checker
import info.kwarc.mmt.api.checking.CheckingResult
import info.kwarc.mmt.api.frontend.actions.Check
import info.kwarc.mmt.api.checking.StructureChecker

import documents._
import modules._
import archives._
import java.util.ResourceBundle.Control
import info.kwarc.mmt.api.frontend.Controller
import info.kwarc.mmt.lf._
import scala.util.control.Exception.Catch
import com.sun.org.glassfish.external.statistics.Statistic

/**
 * An ABoxStore stores the abox of the loaded elements with respect to the MMT ontology.
 *
 * Triples (subject, binary, object) are hashed three ways so that for any two components
 * the set of third components can be retrieved efficiently.
 *
 * Use [[TheoryGraph]] for theory graph-level querying
 */
class RelStore(report : frontend.Report) {
   private val individuals = new HashMapToSet[Unary, Path]
   private val types = new HashMap[Path,Unary]
   private val subjects = new HashMapToSet[(Binary,Path), Path]
   private val objects = new HashMapToSet[(Path,Binary), Path]
   private val dependencies = new HashMapToSet[(Path,Path), Binary]

   override def toString = "Individuals: "+individuals.map(i => "\n - "+i.toString)+"\n Theories:"+
     individuals(IsTheory).map(i => "\n - "+i.toString)

   private def log(msg : => String) = report("abox", msg)
   /** retrieves all Individual declarations */
   def getInds : Iterator[Individual] = individuals.pairs map {case (t,p) => Individual(p,t)}
   /** retrieves all individual of a certain type */
   def getInds(tp: Unary) : Iterator[Path] = individuals(tp).iterator
   /** retrieves type of an individual */
   def getType(p: Path) : Option[Unary] = types.get(p)
   /** checks if an individual has a given type */
   def hasType(p: Path, tp: Unary) : Boolean = individuals(tp) contains p
   /** retrieves all Relation declarations */
   def getDeps : Iterator[Relation] = dependencies.pairs map {case ((p,q), d) => Relation(d,p,q)}
   /** tests if there is a relation holds between two individuals */
   def hasDep(from: Path, to: Path, bin: Binary) = dependencies((from,to)) contains bin

   //def getObjects(d : Binary) = subjects.keys.filter(_._1 == d).map(_._2).toSet
   //def getSubjects(d : Binary) = objects.keys.filter(_._2 == d).map(_._1).toSet

   /** adds a RelationalElement */
   def +=(d : RelationalElement) {
      synchronized {
         log(d.toString)
         d match {
           case Relation(dep, subj, obj) =>
              subjects += ((dep, obj), subj)
              objects += ((subj, dep), obj)
              dependencies += ((subj, obj), dep)
           case Individual(p, tp) =>
              types(p) = tp
              individuals += (tp, p)
              /*
              p.ancestors match {
                 case `p` :: tail =>
                    tail.foldLeft(p)((q1,q2) => {objects += ((q2,Declares),q1) ; q2}) // seems to be necessary to add theories to their namespaces
                 case _ =>
              } */
         }
      }
   }

   /** deletes all RelationalElements with a given subject */
   def deleteSubject(subj : Path) {
      synchronized {
        types -= subj
        individuals.values.foreach {v => v -= subj}
        subjects.values.foreach {v => v -= subj}
        objects.keys.foreach {k =>
           if (k._1 == subj)
              objects -= k
        }
        dependencies.keys.foreach {k =>
           if (k._1 == subj)
              dependencies -= k
        }
      }
   }

   def queryList(start : Path, q : RelationExp) : List[Path] = {
      var ps : List[Path] = Nil
      query(start, q) {p => ps ::= p}
      ps
   }
   def querySet(start : Path, q : RelationExp) : HashSet[Path] = {
      var ps = new HashSet[Path]()
      query(start, q) {p => ps += p}
      ps
   }
   /**
    * Executes a relational query from a fixed start path.
    * There is no result set; instead, a continuation is passed that can be used to build the result set;
    * this permits, e.g., to keep track of the order in which results were found.
    * @param start the MMTURI to which the results are related
    * @param q the query to be executed; the way in which results are related to the start
    * @param add a continuation called on every element in the result set (in topological order, duplicate calls possible)
    */
   def query(start : Path, q : RelationExp)(implicit add : Path => Unit) {q match {
      case ToObject(d) => objects(start, d).foreach(add)   //all paths related to start via d
      case ToSubject(d) => subjects(d, start).foreach(add) //all paths inversely related to start via d
      //only start itself
      case Reflexive => add(start)
      //the set of paths related to start via arbitrarily many q-steps (depth-first, children before parent)
      case Transitive(qn) =>
         var added = HashSet.empty[Path]
         def step(p : Path) {
            if (! added.contains(p)) {
               //println("Added path "+p.toString()+" as a path related to the starting path "+start.toString()+" with search query "+q.toString())
               added += p
               val next = query(p, qn)(step)
               add(p) //add parent only after children
            }
         }
         val next = query(start, qn)(step)
      //the set of paths related to start by any of the relations in qs (in the order listed)
      case Choice(qs @ _*) => qs.foreach(q => query(start, q))
      //the set of paths related to start by making steps according to qs
      case Sequence(qs @ _*) => qs.toList match {
         case Nil => add(start)
         case hd :: tl => query(start, hd) {p => query(p, Sequence(tl : _*))}
      }
      //only start itself iff it has the right type
      case HasType(mustOpt,mustNot) =>
        val tpO = getType(start)
        val inMust = mustOpt match {
          case None => true
          case Some(must) => tpO.map {tp => must contains tp}.getOrElse(false)
        }
        lazy val notInMustNot = tpO.map(tp => ! (mustNot contains tp)).getOrElse(true)
        if (inMust && notInMustNot)
          add(start)
   }}

   /**
    * Discriminates the different sorts of constants into malformatted (malformatted URI), untyped, maltyped and typed constants,
    *  type constructor or statements, kinds and types of type universe >2
    * @param s the type of the constant
    * @param p the path of the constant
    * @param the controller (needed to retrieve type information for the constant)
    */
   def mapConstant(s:Unary, p:Path,con:Controller) = {
     //TODO: discriminate constants of different universes
     if (s == IsConstant && s.toString == "constant") {
       try {
         val (dop, lo, mo) = p.toTriple
         val d=dop getOrElse {throw new Exception("Corrupted path "+p.toString()+". ")}
         val l=lo getOrElse {throw new Exception("Corrupted path "+p.toString()+". ")}
         val m=mo getOrElse {throw new Exception("Corrupted path "+p.toString()+". ")}
         val gnP = GlobalName(MPath(d,l),m)
         val t = con.getConstant(gnP)
         val tp = t.tp
         tp match {
           case Some(Univ(1)) => ("type constructor or statement", p)
           case Some(Univ(2)) => ("kind", p)
           case Some(Univ(n)) if n > 2 => ("type of type universe >2", p) 
           case None => ("untyped constant", p)
           case Some(_) => ("typed constant",p)
         }
       } catch {
         case e:Exception => ("malformatted constant", p)
         case t: Throwable => t.printStackTrace() 
         ("maltyped constant", p)
       }
     }
     else {
       (s.toString, p)
     }
   }
   
   /**
    * Discriminates the a list of constants into the sorts malformatted (malformatted URI), untyped, maltyped and typed constants,
    *  type constructor or statements, kinds and types of type universe >2
    * @param p the list of constants
    * @param the controller (needed to retrieve type information for the constants)
    */
   def mapConstants(p:(Option[Unary], List[Path]),c:Controller) : List[(String, Path)]= {
     val res = p match {
       case (Some(t),x::l) => mapConstant(t, x,c:Controller)::mapConstants((Some(t),l),c:Controller)
       case (Some(p), Nil) => Nil
       case (None, _) => Nil
     }
     res
   }
   
  /**
    * Make a statistic for the given query and prepend the prefix to the descriptions of the found declarations
    * @param p the path of the document of theory to make the statistic for
    * @param q the query
    * @param the controller (needed to retrieve type information for the constant)
    */ 
  def makeStatisticsFor(p:Path, q:RelationExp, prefix:String, con:Controller) = {
    val ds=querySet(p, q)
    val dsGl = ds.toList.groupBy(x => getType(x)).toList flatMap {x => mapConstants(x,con)}
      val dsG = dsGl.groupBy({case (s,x)=>s}).toList flatMap {
      case (s:String,l) => (List((prefix+s,l.size)))
    }
    Statistics(dsG)
  }
   
  /**
    * Make a statistic for the document or theory at the given path
    * @param p the path of the document or theory
    * @param the controller (needed to retrieve type information for the constants)
    */
  def makeStatistics(p: Path, con:Controller) = {
    val decl = Transitive(+Declares)
    val align = decl * Transitive(+IsAlignedWith)
    // Should also morphisms to subtheories be counted?
    // val subtheory = Transitive(+Declares *HasType(IsTheory) | Reflexive)
    val expMorph = Transitive(+HasViewFrom)
    val morph = Transitive(+HasMeta | +Includes | +IsImplicitly | +HasViewFrom)
    val expinduced = expMorph * +Declares * HasType(IsConstant)
    val induced = morph * +Declares * HasType(IsConstant)
    var dsG = makeStatisticsFor(p, decl, "",con)
    dsG += makeStatisticsFor(p, align, "Alignments of ",con)
    val (exMorph, anyMorph) = (querySet(p, expMorph).size, querySet(p, morph).size)
    if (exMorph > 0)
      dsG += ("Explicit theory morphisms", exMorph)
    if (anyMorph > 0)
      dsG += ("Any theory morphisms", anyMorph)
    dsG += makeStatisticsFor(p, expinduced, "Induced declarations via explicit theory morphisms of ",con)
    dsG += makeStatisticsFor(p, induced, "Induced declarations via any theory morphisms of ",con)
    dsG
  }

   /**
    * Returns the set of theories a theory depends on
    */
   def theoryClosure(p : MPath) : List[MPath] = {
      val q = Transitive(+HasMeta | +Includes | +DependsOn | Reflexive)
      var l : List[MPath] = Nil
      query(p, q) {
         case p : MPath => l ::= p
         case _ =>
      }
      l
   }
   /** deletes all declarations */
   def clear {
      this.synchronized {
         dependencies.clear
         subjects.clear
         objects.clear
         individuals.clear
         types.clear
      }
   }
}

case class Statistics(entries: List[(String,Int)]) {
  def +(that: Statistics): Statistics = {
    Statistics(entries ::: that.entries)
  }
  def +(s: String, n: Int): Statistics = {
    this + Statistics(List((s,n)))
  }
}

sealed abstract class StatisticEntries {
  def description : String
  def +(s: String): StatisticEntries = {
    def description = {s+this.description}
    this
  }
}
sealed abstract class IndStatisticEntries extends StatisticEntries {
  def description : String
  def fromStatisticEntries(e:StatisticEntries) : this.type = {
    def description = {e.description}
    this
  }
}

sealed abstract class ExpIndStatisticEntries extends IndStatisticEntries {
  def description : String
  def fromStatisticEntries(e:IndStatisticEntries) : this.type = {
    def description = {e.description}
    this
  }
}
case class Theory() extends StatisticEntries {
  def description = {"theory"}
}
case class Document() extends StatisticEntries {
  def description = {"document"}
}
case class UntypedConstant() extends StatisticEntries {
  def description = {"untyped constant"}
}
case class TypedConstant() extends StatisticEntries {
  def description = {"typed constant"}
}
case class MalformattedConstant() extends StatisticEntries {
  def description = {"malformatted constant"}
}
case class MalformedConstant() extends StatisticEntries {
  def description = {"malformed constant"}
}
case class Structure() extends StatisticEntries {
  def description = {"structure"}
}
class TypeConstructor extends StatisticEntries {
  def description = {"type constructor or statement"}
}
case class View() extends StatisticEntries {
  def description = {"view"}
}
case class Kind() extends StatisticEntries {
  def description = {"kind"}
}
case class ExplicitMorphism() extends StatisticEntries {
  def description = {"explicit theory morphisms"}
}
case class AnyMorphism() extends StatisticEntries {
  def description = {"any theory morphism"}
}

case class ExpIndUntypedConstant() extends ExpIndStatisticEntries {
  def description = {"untyped constant"}
}
case class ExpIndTypedConstant() extends ExpIndStatisticEntries {
  def description = {"typed constant"}
}
case class ExpIndMalformattedConstant() extends ExpIndStatisticEntries {
  def description = {"malformatted constant"}
}
case class ExpIndMalformedConstant() extends ExpIndStatisticEntries {
  def description = {"malformed constant"}
}
case class ExpIndStructure() extends ExpIndStatisticEntries {
  def description = {"structure"}
}
case class ExpIndTypeConstructor() extends ExpIndStatisticEntries {
  def description = {"type constructor or statement"}
}

case class IndUntypedConstant() extends IndStatisticEntries {
  def description = {"untyped constant"}
}
case class IndTypedConstant() extends IndStatisticEntries {
  def description = {"typed constant"}
}
case class IndMalformattedConstant() extends IndStatisticEntries {
  def description = {"malformatted constant"}
}
case class IndMalformedConstant() extends IndStatisticEntries {
  def description = {"malformed constant"}
}
case class IndStructure() extends IndStatisticEntries {
  def description = {"structure"}
}
case class IndTypeConstructor() extends IndStatisticEntries {
  def description = {"type constructor or statement"}
}
