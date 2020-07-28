package info.kwarc.mmt.frameit.communication

import cats.effect.IO
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import info.kwarc.mmt.api
import info.kwarc.mmt.api.frontend.Controller
import info.kwarc.mmt.api.metadata.MetaDatum
import info.kwarc.mmt.api.modules.{Module, Theory, View}
import info.kwarc.mmt.api.notations.NotationContainer
import info.kwarc.mmt.api.objects.OMMOD
import info.kwarc.mmt.api.ontology.IsTheory
import info.kwarc.mmt.api.symbols.{FinalConstant, PlainInclude, TermContainer, Visibility}
import info.kwarc.mmt.api._
import info.kwarc.mmt.api.presentation.{MMTSyntaxPresenter, RenderingHandler}
import info.kwarc.mmt.frameit.archives.Archives
import info.kwarc.mmt.frameit.archives.Foundation.StringLiterals
import info.kwarc.mmt.frameit.business.{DebugUtils, Fact, Scroll, TheoryUtils}
import info.kwarc.mmt.moduleexpressions.operators.NamedPushoutUtils
import io.finch._
import io.finch.circe._
import io.finch._
import shapeless._

import scala.util.Random

sealed abstract class ValidationException(message: String, cause: Throwable = None.orNull)
  extends Exception(message, cause)

final case class FactValidationException(message: String, cause: Throwable = None.orNull) extends ValidationException(message, cause)

/**
  * A collection of REST routes for our [[Server server]]
  */
object ServerEndpoints extends EndpointModule[IO] {
  private def getEndpointsForState(state: ServerState) =
    buildArchiveLight(state) :+: buildArchive(state) :+: addFact(state) :+: listFacts(state) :+: listScrolls(state) :+: applyScroll(state) :+: printSituationTheory(state)

  def getServiceForState(state: ServerState): Service[Request, Response] =
    getEndpointsForState(state).toServiceAs[Application.Json]

  // ENDPOINTS (all private functions)
  // ======================================
  private def buildArchiveLight(state: ServerState): Endpoint[IO, Unit] = post(path("archive") :: path("build-light")) {
    state.ctrl.handleLine(s"build ${Archives.FrameWorld.archiveID} mmt-omdoc Scrolls/OppositeLen.mmt")

    Ok(())
  }

  private def buildArchive(state: ServerState): Endpoint[IO, Unit] = post(path("archive") :: path("build")) {
    state.ctrl.handleLine(s"build ${Archives.FrameWorld.archiveID} mmt-omdoc")

    Ok(())
  }

  private def addFact(state: ServerState): Endpoint[IO, Unit] = post(path("fact") :: path("add") :: jsonBody[SNewFact]) {
    (fact: SNewFact) => {
      // create MMT declaration out [[SNewFact]]
      val factConstant = new FinalConstant(
        home = state.situationTheory.toTerm,
        name = LocalName(SimpleStep(fact.label)),
        alias = Nil,
        tpC = TermContainer.asParsed(SOMDoc.OMDocBridge.decode(fact.tp)),
        dfC = TermContainer.asParsed(fact.df.map(SOMDoc.OMDocBridge.decode)),
        rl = None,
        notC = new NotationContainer,
        vs = Visibility.public
      )

      factConstant.metadata.add(MetaDatum(Archives.FrameWorld.MetaKeys.factLabel, StringLiterals(fact.label)))

      state.contentValidator.checkDeclarationAgainstTheory(state.situationTheory, factConstant) match {
        case Nil =>
          // success (i.e. no errors)
          state.ctrl.add(factConstant)
          Ok(())

        case errors =>
          NotAcceptable(FactValidationException(
            "Could not validate fact, errors were:\n\n" + errors.mkString("\n")
          ))
      }
    }
  }

  private def listFacts(state: ServerState): Endpoint[IO, List[SFact]] = get(path("fact") :: path("list")) {
    val facts = TheoryUtils.getAllFinalConstantsRecursively(state.situationTheory)(state.ctrl)
      .map(Fact.parseFromDeclaration)
      .map(_.simplified)

    Ok(facts)
  }

  private def printSituationTheory(state: ServerState): Endpoint[IO, String] = get(path("debug") :: path("situationtheory") :: path("print")) {
    state.ctrl.extman.getOrAddExtension(classOf[MMTSyntaxPresenter], "present-text-notations") match {
      case None =>
        InternalServerError(GeneralError("could not get MMTSyntaxPresenter extension required for printing"))

      case Some(presenter) =>
        val stringRenderer = new presentation.StringBuilder
        DebugUtils.syntaxPresentRecursively(state.situationTheory)(state.ctrl, presenter, stringRenderer)

        Ok(stringRenderer.get)
    }
  }

  private def listScrolls(state: ServerState): Endpoint[IO, List[SScroll]] = get(path("scroll") :: path("list")) {
    val allTheories = state.ctrl.depstore.getInds(IsTheory).map(_.asInstanceOf[MPath]).map(state.ctrl.getTheory)

    val scrolls = allTheories.flatMap(t => Scroll.fromTheory(t)(state.ctrl.globalLookup) match {
      case Right(scroll) => Some(scroll)
      case Left(err) =>
        state.log(s"Ignoring theory ${t} due to error below. Note that theories that are not scrolls also emit such errors.")
        state.log(err.toString)
        None
    }).map(_.simplified).toList

    Ok(scrolls)
  }

  private sealed case class ScrollApplicationNames(view: LocalName, pushedOutView: LocalName, situationTheoryExtension: LocalName)

  /**
    * Generate names for the scroll view and the view generated by pushing over it.
    */
  private def generateScrollApplicationNames(state: ServerState): ScrollApplicationNames = {
    val r = Random.nextInt()
    ScrollApplicationNames(
      LocalName(s"frameit_scroll_view_${r}"), // TODO: improve this, let game engine dictate one?
      LocalName(s"frameit_pushed_scroll_view_${r}"),  // TODO: improve this, let game engine dictate one?
      LocalName(s"frameit_ext_situation_theory_${r}")
    )
  }

  private def applyScroll(state: ServerState): Endpoint[IO, List[SFact]] = post(path("scroll") :: path("apply") :: jsonBody[SScrollApplication]) { (scrollApp: SScrollApplication) => {

    val scrollViewDomain = scrollApp.scroll.problemTheory
    val scrollViewCodomain = state.situationTheoryPath

    val ScrollApplicationNames(scrollViewName, pushedOutScrollViewName, situationTheoryExtensionName) = generateScrollApplicationNames(state)

    // create view out of [[SScrollApplication]]
    val scrollView = new View(
      doc = state.situationDocument,
      name = scrollViewName,
      fromC = TermContainer.asParsed(OMMOD(scrollViewDomain)),
      toC = TermContainer.asParsed(OMMOD(scrollViewCodomain)),
      dfC = TermContainer.empty(),
      isImplicit = false
    )

    val scrollViewAssignments = scrollApp.assignments.map {
      case (factRef, assignedTerm) =>
        // create new assignment
        new FinalConstant(
          home = scrollView.toTerm,
          name = LocalName(ComplexStep(factRef.uri.module) :: factRef.uri.name),
          alias = Nil,
          tpC = TermContainer.empty(),
          dfC = TermContainer.asParsed(SOMDoc.OMDocBridge.decode(assignedTerm)),
          rl = None,
          notC = new NotationContainer,
          vs = Visibility.public,
        )
    }

    state.ctrl.add(scrollView)
    scrollViewAssignments.foreach(state.ctrl.add(_))

    state.contentValidator.checkView(scrollView) match {
      case Nil =>
        // TODO: perform pushout, add new facts to situation theory, and return new facts

        val (situationTheoryExtension, pushedOutView) = NamedPushoutUtils.computeCanonicalPushoutAlongDirectInclusion(
          state.ctrl.getTheory(scrollViewDomain),
          state.ctrl.getTheory(scrollViewCodomain),
          state.ctrl.getTheory(scrollApp.scroll.solutionTheory),
          state.situationDocument ? situationTheoryExtensionName,
          scrollView,
          w_to_generate = state.situationDocument ? pushedOutScrollViewName
        )

        state.ctrl.add(situationTheoryExtension)
        state.ctrl.add(pushedOutView)
        state.setSituationTheory(situationTheoryExtension)

        Ok(List[SFact]())

      case errors =>
        state.ctrl.delete(scrollView.path)

        NotAcceptable(FactValidationException(
          "View for scroll application does not validate, errors were:\n\n" + errors.mkString("\n")
        ))
    }
  }}
  /*
    private def getHintsForPartialScroll(gameLogic: FrameItLogic): Endpoint[IO,String] = post(path("scroll") :: path("hints") :: stringBody) {data: String => {
      /**
        * Example:
        * {
        *   domainTheory: http://...?SituationTheory,
        *   scroll: {
        *     "problem": http://...?OppositeLen_Problem,
        *     "solution": // unused
        *   },
        *   assignments: {
        *     "http://...?pA": {x: ..., y: ..., z: ...}
        *     ...
        *   }
        * }
        */
      JSON.parseFull(data)
      Ok("abc")
    }}

    def getPushOut(gameLogic: FrameItLogic): Endpoint[IO,String ] =
      get(
        path("pushout")
          ::param("problem")
          ::param("solution")
          ::param("view")
      ){
      (prob : String,sol:String,view:String ) =>{
        val ret = gameLogic.applyScroll(prob, sol,view)
        if(ret.isDefined) Ok(ret.get) else BadRequest(new IllegalArgumentException())
      }
    }

  */
}