package info.kwarc.mmt.leo.AgentSystem.GoalSystem

import info.kwarc.mmt.api.symbols.Constant
import info.kwarc.mmt.api.{Active, modules, RuleSet}
import info.kwarc.mmt.api.frontend.Controller
import info.kwarc.mmt.api.objects._
import info.kwarc.mmt.leo.AgentSystem.AndOrSystem.{AndOrBlackboard, AndOrSection}
import info.kwarc.mmt.leo.AgentSystem.{Section, Blackboard}

/**
 * Created by Mark on 7/21/2015.
 *
 * This represents the class of the LF blackboard which handles proofs in the LF prover
 */
class GoalBlackboard(val controller: Controller, val rules:RuleSet,goal: Goal) extends Blackboard {
  override def logPrefix = "Goal Blackboard"

  val proofSection = new GoalSection(this,goal)
  addSection(proofSection)
  log("Added Goal of type: " + goal.getClass + goal)
  log(proofSection.toString)

  val shapeDepth = 2
  val factSection = new FactSection(this, shapeDepth)
  addSection(factSection)
  initFacts()//TODO work facts into changes/section interface
  //def facts = factSection.data
  implicit val facts =factSection.data
  def factsChanges = factSection.changes

  val invertibleBackward = rules.get(classOf[BackwardInvertible]).toList
  val invertibleForward  = rules.get(classOf[ForwardInvertible]).toList
  val searchBackward     = rules.get(classOf[BackwardSearch]).toList.sortBy(_.priority).reverse
  val searchForward      = rules.get(classOf[ForwardSearch]).toList

  implicit val presentObj: Obj => String = o => controller.presenter.asString(o)
  val report = controller.report

  /** convenience function to create a matcher in the current situation */
  def makeMatcher(context: Context, queryVars: Context) = new Matcher(controller, rules, context, queryVars)

  private def initFacts() {
    val imports = controller.library.visibleDirect(ComplexTheory(goal.context))
    imports.foreach {
      case OMPMOD(p,_) =>
        controller.globalLookup.getO(p) match {
          case Some(t:modules.DeclaredTheory) =>
            t.getDeclarations.foreach {
              case c: Constant if c.status == Active => c.tp.foreach {tp =>
                val a = Atom(c.toTerm, tp, c.rl)
                facts.addConstantAtom(a)
              }
              case _ =>
            }
          case _ =>
        }
      case _ =>
    }
  }



  /** Boolean representing the status of the prof goal */
  override def finished: Boolean = goal.isFinished
}

object Indent {
  def indent(depth: Int) = (0 to depth).map(_ => "  ").mkString("")
}
