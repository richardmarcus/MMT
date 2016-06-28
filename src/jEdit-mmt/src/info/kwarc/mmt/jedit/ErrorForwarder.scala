package info.kwarc.mmt.jedit

import errorlist._
import info.kwarc.mmt.api._
import archives.source
import frontend._
import objects._
import parser._
import utils.MyList._
import utils._

/** customizes the default errors of the ErrorList plugin
 *  @param mainFile the file whose checking led to the error (may differ from the file that contains the error)
 */
class MMTError(val mainFile: File, es: ErrorSource, el: Int, sf: String, sl: Int, sc: Int, ec: Int, msg: String)
   extends DefaultErrorSource.DefaultError(es, el, sf, sl, sc, ec, msg)

/** customizes the default error source of the ErrorList plugin */
class MMTErrorSource extends DefaultErrorSource("MMT") {

   /** like superclass but allows only [[MMTError]]s */
   override def addError(e: DefaultErrorSource.DefaultError) {
      e match {
        case e: MMTError => super.addError(e)
        case _ => throw ImplementationError("illegal error")
      }
   }
  
   /** remove errors produced when checking this file */
   def removeFileErrors(file: File) {
     val sets = errors.values.iterator
     while (sets.hasNext) {
       val set = sets.next
       val es = set.iterator
       var remove: List[MMTError] = Nil
       while (es.hasNext) {
          es.next match {
            case e: MMTError =>
               if (e.mainFile == file) {
                 remove ::= e
               }
            case e =>
               // should be impossible
          }
       }
       remove.foreach {e =>
         set.remove(e)
            gui.Swing.invokeLater {
              val msg = new ErrorSourceUpdate(this, ErrorSourceUpdate.ERROR_REMOVED, e)
         		  org.gjt.sp.jedit.EditBus.send(msg)
         	 }
       }
     }
   }
}

/**
 * sends MMT errors directly to jEdit ErrorList
 * @param file the source file, in which the errors are found
 */
class ErrorListForwarder(errorSource: MMTErrorSource, controller: Controller, mainFile: File) extends ErrorHandler {
   /**
    * remove all errors whose mainFile is src
    */
   private var errors : Array[ErrorSource.Error] = Array()
   def reset {
      errorSource.removeFileErrors(mainFile)
      errors = Array()
   }
   protected def addError(e: Error) = e match {
      case s: SourceError =>
         //generated by parsers
         val tp = if (s.level == Level.Warning) ErrorSource.WARNING else ErrorSource.ERROR
         val reg = s.ref.region
         val pos = reg.start
         // We permit the case that errors are found in other files than the current one. So we compute the file path
         val file = controller.backend.resolveLogical(s.ref.container) match {
            case Some((a, p)) => (a / source / p).toString
            case None => s.ref.container match {
               case utils.FileURI(f) => f.toString
               case u => u.toString
            }
         }
         val error = new MMTError(mainFile, errorSource, tp, file, pos.line, pos.column, pos.column + reg.length, s.mainMessage)
         s.extraMessages foreach {m => error.addExtraMessage(m)}
         errorSource.addError(error)
      case e: Invalid =>
         //generated by checkers
         var mainMessage = e.shortMsg
         var extraMessages : List[String] = Nil
         val causeOpt: Option[metadata.HasMetaData] = e match {
            case e: InvalidObject => Some(e.obj)
            case e: InvalidElement => Some(e.elem)
            case e: InvalidUnit =>
               val steps = e.history.getSteps.reverse
               extraMessages = steps.map(_.present(o => controller.presenter.asString(o)))
               val declOpt = e.unit.component.map(p => controller.localLookup.get(p.parent))
               // WFJudgement must exist because we always start with it
               // find first WFJudegment whose region is within the failed checking unit
               // but maybe lastWFJ.wfo has lost its region through simplification?
               declOpt flatMap {decl =>
                  SourceRef.get(decl).flatMap {bigRef =>
                     steps.mapFind {
                        case j: WFJudgement =>
                           SourceRef.get(j.wfo) flatMap {smallRef =>
                              if (bigRef contains smallRef) {
                                 mainMessage += ": " + controller.presenter.asString(j.wfo)
                                 Some(j.wfo)
                              } else
                                 None
                           }
                        case _ => None
                     }
                  }.orElse(declOpt)
               }
         }
         val ref = causeOpt.flatMap {cause => SourceRef.get(cause)}.getOrElse {
            mainMessage = "error with unknown location: " + mainMessage
            SourceRef(utils.FileURI(mainFile), SourceRegion(SourcePosition(0,0,0), SourcePosition(0,0,0)))
         }
         val reg = ref.region
         val error = new MMTError(mainFile, errorSource, ErrorSource.ERROR, mainFile.toString,
               reg.start.line, reg.start.column, reg.start.column + reg.length, mainMessage)
         extraMessages foreach {m => error.addExtraMessage(m)}
         errorSource.addError(error)
      case e: Error =>
         // other errors, should not happen
         val error = new MMTError(mainFile,
             errorSource, ErrorSource.ERROR, mainFile.toString, 0, 0, 1, "error with unknown location: " + e.getMessage
         )
         e.toStringLong.split("\\n").foreach {m => error.addExtraMessage(m)}
         errorSource.addError(error)
   }
}