package info.kwarc.mmt.api.frontend

import info.kwarc.mmt.api._
import info.kwarc.mmt.api.archives._
import utils._
import scala.collection.immutable.{ListMap}


/**
 * .mbt files schould contain a single object that extends MMTScript
 */
abstract class MMTScript extends Extension {
  //MMT Actions
  def logToFile(f : String, ts : Boolean = false) = controller.handle(AddReportHandler(new TextFileHandler(File(f), ts)))
  def logToConsole() = controller.handle(AddReportHandler(ConsoleHandler))
  def logToHTML(f : String) = controller.handle(AddReportHandler(new HtmlFileHandler(File(f))))
  def logModule(s : String) = controller.handle(LoggingOn(s))
  def addArchive(location : String) = controller.handle(AddArchive(File(location)))
  def loadExtension(uri : String, args : List[String] = Nil) = controller.handle(AddExtension(uri, args))
  
  //Utility
  
  abstract class Compiler(val uri : String, val key : String, val args : List[String]) 
  case class Importer(override val uri : String, override val key : String, override val args : List[String]) extends Compiler(uri, key, args)
  case class Exporter(override val uri : String, override val key : String, override val args : List[String]) extends Compiler(uri, key, args)
  case class Archive(id : String, formats : List[String])
  case class Format(id : String, importers : List[String], exporters : List[String])
  object config {
    var base : String = ""
    var importers : List[Importer] = Nil
    var exporters : List[Exporter] = Nil
    var archives : List[Archive] = Nil
    var formats : List[Format] = Nil
    
    def addArchives() {
      config.archives foreach { arch => 
        addArchive(base + arch.id)
      }
    }
    
    def getArchive(aid : String) = archives.find(_.id == aid).getOrElse(throw new Exception("Unknown archive id: " + aid))
    def getImporters(formatId : String) = formats.find(_.id == formatId).getOrElse(throw new Exception("Unknown format id: " + formatId)).importers
    def getExporters(formatId : String) = formats.find(_.id == formatId).getOrElse(throw new Exception("Unknown format id: " + formatId)).exporters
    
    
    def runImporters(aid : String, btm : BuildTargetModifier = Build) = {
      getArchive(aid).formats.flatMap(getImporters).distinct foreach {imp =>
        build(List(aid), imp, btm)
      }
    }
    
    def runExporters(aid : String, btm : BuildTargetModifier = Build) = {
      getArchive(aid).formats.flatMap(getImporters).distinct foreach {imp =>
        build(List(aid), imp, btm)
      }
    }
    
    def cleanBuild() {
      archives.foreach(a => runImporters(a.id, Clean))
      archives.foreach(a => runImporters(a.id, Build))
      archives.foreach(a => runExporters(a.id, Clean))
      archives.foreach(a => runExporters(a.id, Build))
    }
    
    def updateBuild(ifHadErrors : Boolean) {
      archives.foreach(a => runImporters(a.id, Update(ifHadErrors)))
      archives.foreach(a => runExporters(a.id,  Update(ifHadErrors)))
    }
    
    def plainBuild(btm : BuildTargetModifier) = {
      archives.foreach(a => runImporters(a.id, btm))
      archives.foreach(a => runExporters(a.id, btm))
    }
    
    def compUpdateBuild(changedCompsIds : List[String], btm : BuildTargetModifier) = {
       archives foreach {a => 
         getArchive(a.id).formats foreach {f => 
           val imps = getImporters(f)
           val exps = getExporters(f)
           var foundChanged = false
           imps foreach { imp => 
             if (changedCompsIds.contains(imp)) foundChanged = true
             if (foundChanged) build(List(a.id), imp, Build)
           }
           
           exps foreach { exp =>
             if (changedCompsIds.contains(exp) || foundChanged) build(List(a.id), exp, Build)
           }
         }
       }
    }
    
    def loadActiveExtensions() { 
      val activeFormats = archives.flatMap(_.formats).toSet[String] map {id => 
        formats.find(_.id == id).getOrElse(throw new Exception("Unknown format id: " + id))
      }
      
      val activeImporters = activeFormats.flatMap(_.importers).toSet[String] map {key => 
        importers.find(_.key == key).getOrElse(throw new Exception("Unknown importer key: " + key))
      }
      val activeExporters = activeFormats.flatMap(_.exporters).toSet[String] map {key => 
        exporters.find(_.key == key).getOrElse(throw new Exception("Unknown exporter key: " + key))
      }
      
      (activeImporters ++ activeExporters) foreach {comp => 
        println("loading " + comp.uri)
        loadExtension(comp.uri, comp.args)
      }
    }

    
    def parse(f : String, autoload : Boolean = true) = {
      val file = File(f)
      val s = File.read(file)
      val lines = s.split("\n")
      var section = ""
      for (line <- lines) {
        if (line.startsWith("//")) {
          //ignore
        } else if (line.startsWith("#")) {
          section = line.substring(1)
        } else section match {
          case "importers" => line.split(" ").toList match {
            case uri :: key :: args => 
              config.importers ::= Importer(uri, key, args)
              if (autoload) loadExtension(uri, args)
            case _ => println("Invalid importer line: `" + line + "`")
          }
          case "exporters" => line.split(" ").toList match {
            case uri :: key :: args => 
              config.exporters ::= Exporter(uri, key, args)
              if (autoload) loadExtension(uri, args)
            case _ => println("Invalid exporter line: `" + line + "`")
          }
          case "archives" => line.split(" ").toList match {
            case id :: fmtsS :: Nil => 
              val fmts = fmtsS.split(",").toList
              config.archives ::= Archive(id, fmts)
              if (autoload) addArchive(base + id)
            case _ => println("Invalid archives line: `" + line + "`")
          }
          case "formats" => line.split(" ").toList match {
            case id :: impsS :: expsS :: Nil => 
              val imps = impsS.split(",").toList
              val exps = expsS.split(",").toList
              config.formats ::= Format(id, imps, exps)
            case _ => println("Invalid formats line: `" + line + "`")
          }
          case "base" => base = line
          case s => println("ignoring invalid section: `" + s + "`") 
        }
      }
    }
  }
  
  
  def build(ids : List[String], target : String, modifier: archives.BuildTargetModifier, in : FilePath = EmptyPath) : Unit = {
    controller.handle(ArchiveBuild(ids, target, modifier, in))
  }
  
   def main()
}

class MMTScriptEngine(controller: Controller) {
   def apply(f: File) {
     val code = File.read(f)
     val imports = "import info.kwarc.mmt.api._\nimport info.kwarc.mmt.api.archives.{Build}\n"
     val textscript = imports + s"object UserScript extends info.kwarc.mmt.api.frontend.MMTScript {\ndef main() {\n$code\n}\n}\nUserScript"
     import scala.reflect.runtime._
     val cm = universe.runtimeMirror(getClass.getClassLoader)
     import scala.tools.reflect.ToolBox
     val tb = cm.mkToolBox()
     //println(textscript)
     val scalascript = tb.eval(tb.parse(textscript))
     scalascript match {
        case s: MMTScript =>
           s.init(controller)
           s.main()
        case _ => 
     }
   }
}