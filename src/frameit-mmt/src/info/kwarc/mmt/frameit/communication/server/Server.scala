package info.kwarc.mmt.frameit.communication.server

import java.net.InetSocketAddress

import com.twitter.finagle.Http
import com.twitter.server.TwitterServer
import com.twitter.util.Await
import info.kwarc.mmt.api.frontend.{ConsoleHandler, Controller}
import info.kwarc.mmt.api.modules.Theory
import info.kwarc.mmt.api.utils.{File, FilePath}
import info.kwarc.mmt.api.{DPath, GetError, LocalName}
import info.kwarc.mmt.frameit.archives.FrameIT.FrameWorld

object Server extends TwitterServer {
  override def failfastOnFlagsNotParsed: Boolean = true

  private val debug = flag("debug", false, "Server in debug mode?")
  private val bindAddress = flag("bind", new InetSocketAddress(8080), "Bind address")
  private val archiveRoot = flag("archive-root", "", "Path to archive root (preferably without spaces), e.g. to a clone of <https://github.com/UFrameIT/archives>")

  def main(): Unit = {
    if (debug()) {
      println("Server started in debugging mode.")
    }

    val state = initServerState(File(archiveRoot()))
    val server = Http.serve(bindAddress(), ServerEndpoints.getServiceForState(state))
    onExit {
      server.close()
    }
    Await.ready(server)
  }

  def initServerState(archiveRoot: File): ServerState = {
    val ctrl = new Controller()
    ctrl.report.addHandler(ConsoleHandler)

    ctrl.handleLine(s"mathpath archive ${archiveRoot}")
    val frameitArchive = ctrl.backend.getArchive(FrameWorld.archiveID).getOrElse {
      throw GetError(s"Archive ${FrameWorld.archiveID} could not be found!")
    }

    // force-read relational data as somewhere (TODO say where) we use the depstore
    // to get meta tags on things
    frameitArchive.readRelational(FilePath("/"), ctrl, "rel")

    val situationTheory: Theory = if (debug()) {
      println(s"Debug mode: trying to use situation theory `${FrameWorld.situationTheoryForDebugging}`...")

      ctrl.getTheory(FrameWorld.situationTheoryForDebugging)
    } else {
      println("Release mode: setting up empty situation theory...")

      val situationTheory = Theory.empty(
        DPath(frameitArchive.narrationBase),
        LocalName("SituationTheory"),
        Some(FrameWorld.metaTheoryForSituationTheory)
      )
      ctrl.add(situationTheory)

      situationTheory
    }

    val state = new ServerState(ctrl, situationTheory.path.parent, situationTheory.path)
    state.doTypeChecking = false // TODO, due to persisting MMT errors Florian is currently about to fix

    (if (state.doTypeChecking) state.contentValidator.checkTheory(situationTheory) else Nil) match {
      case Nil =>
        println("Situation theory successfully set-up and typechecked (the latter only in release mode).")
        state

      case errors =>
        sys.error("Created situation theory, but cannot successfully typecheck it. Server will not be started. Errors below:")
        sys.error(errors.mkString("\n"))
        throw new Exception("")
    }
  }
}
