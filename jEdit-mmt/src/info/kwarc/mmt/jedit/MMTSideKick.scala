package info.kwarc.mmt.jedit
import org.gjt.sp.jedit._
import errorlist._
import sidekick._

import info.kwarc.mmt.api._
import parser._
import frontend._
import libraries._
import modules._
import patterns._
import objects._
import symbols._
import documents._
import utils.File
import utils.MyList.fromList

import javax.swing.tree.DefaultMutableTreeNode
import scala.collection.JavaConversions.seqAsJavaList

case class MyPosition(offset : Int) extends javax.swing.text.Position {
   def getOffset = offset
}

/* node in the sidekick outline tree: common ancestor class */ 
abstract class MMTAsset(name: String, val region: SourceRegion)
  extends enhanced.SourceAsset(name, region.start.line, MyPosition(region.start.offset)) {
  setEnd(MyPosition(region.end.offset))
  def getScope : Option[Term]
}

/* node in the sidekick outline tree: declarations */ 
class MMTDeclAsset(val elem : StructuralElement, name: String, reg: SourceRegion) extends MMTAsset(name, reg) {
   //note: shortDescription == name, shown in tree
   setLongDescription(path.toPath)  // tool tip
   //setIcon
   def path = elem.path
   def getScope : Option[objects.Term] = elem match {
      case _ : NarrativeElement => None
      case _ : PresentationElement => None
      case c : ContentElement => c match {
        case t: DeclaredTheory => Some(objects.OMMOD(t.path))
        case v: modules.View => None //would have to be parsed to be available
        case d: Declaration => Some(d.home)
        case _ => None
      }
   }
}

/* node in the sidekick outline tree: terms */ 
class MMTTermAsset(val parent: ContentPath, val path: Option[Path], name: String, reg: SourceRegion) extends MMTAsset(name, reg) {
  path map {case p =>
    setLongDescription(p.toPath)
  }
  def getScope = Some(OMID(parent))
}

// text is the string that is to be completed, items is the list of completions
class MyCompletion(view : org.gjt.sp.jedit.View, text: String, items: List[String])
  extends SideKickCompletion(view, text, items) {
   // override def insert(index: Int) // this methods modifies the textArea after the user selected a completion
}

class MMTSideKick extends SideKickParser("mmt") with Logger {
   // gets jEdit's instance of MMTPlugin, jEdit will load the plugin if it is not loaded yet
   val mmt : MMTPlugin = jEdit.getPlugin("info.kwarc.mmt.jedit.MMTPlugin", true).asInstanceOf[MMTPlugin]
   val controller = mmt.controller
   val logPrefix = "jedit-parse"
   val report = controller.report
      
   private def getRegion(e: metadata.HasMetaData) : Option[SourceRegion] = SourceRef.get(e).map(_.region)
   /* build the sidekick outline tree: document node */
   private def buildTree(node: DefaultMutableTreeNode, doc: Document) {
      val reg = getRegion(doc) getOrElse SourceRegion(SourcePosition(0,0,0),SourcePosition(0,0,0))
      val child = new DefaultMutableTreeNode(new MMTDeclAsset(doc, doc.path.toPath, reg))
      node.add(child)
      doc.getItems foreach {
        case d: DRef =>
           buildTree(child, controller.getDocument(d.target))
        case m: MRef =>
           buildTree(child, controller.localLookup.getModule(m.target), reg)
      }
   }
   /* build the sidekick outline tree: module node */
   private def buildTree(node: DefaultMutableTreeNode, mod: Module, defaultReg: SourceRegion) {
      val keyword = mod match {case _ : Theory => "theory"; case _: modules.View => "view"}
      val reg = getRegion(mod) getOrElse SourceRegion(defaultReg.start,defaultReg.start)
      val child = new DefaultMutableTreeNode(new MMTDeclAsset(mod, keyword + " " + mod.path.last, reg))
      node.add(child)
      mod match {
         case m: DeclaredModule[_] =>
            m.getPrimitiveDeclarations foreach {d => buildTree(child, d, reg)}
         case m: DefinedModule =>
      }
   }
   /* build the sidekick outline tree: declaration (in a module) node */
   private def buildTree(node: DefaultMutableTreeNode, dec: Declaration, defaultReg: SourceRegion) {
      val label = dec match {
         case PlainInclude(from,_) => "include " + from.last
         case s: Structure => "structure " + s.name.toString
         case a: DefLinkAssignment => "include " + a.name.toString
         case d: Declaration => d.role.toString + " " + d.name.toString
      }
      val reg = getRegion(dec) getOrElse SourceRegion(defaultReg.start,defaultReg.start)
      val child = new DefaultMutableTreeNode(new MMTDeclAsset(dec, label, reg))
      node.add(child)
      dec match {
         //TODO: should be done with a generic function that returns the list of components
         case PlainInclude(from, _) => buildTree(child, dec.path, "from", OMMOD(from), reg)
         case c: Constant =>
             c.tp foreach {t => buildTree(child, dec.path, "type", t, reg)}
             c.df foreach {t => buildTree(child, dec.path, "definition", t, reg)}
         case p: Pattern =>
             //TODO this is just a quick hack
             p.params.variables foreach {vd => vd.tp foreach {t => buildTree(child, dec.path, vd.name.toString, t, reg)}} 
             p.body.variables foreach {vd => vd.tp foreach {t => buildTree(child, dec.path, vd.name.toString, t, reg)}}
         case _ => //TODO other cases, only reasonable once parser is better
      }
   }
   /** build the sidekick outline tree: component of a (module or symbol level) declaration */
   private def buildTree(node: DefaultMutableTreeNode, parent: ContentPath, component: String, t: objects.Term, defaultReg: SourceRegion) {
      val reg = getRegion(t) getOrElse SourceRegion(defaultReg.start,defaultReg.start)
      val child = new DefaultMutableTreeNode(new MMTTermAsset(parent, None, component, reg))
      node.add(child)
      buildTree(child, parent, t, reg)
   }
   /** build the sidekick outline tree: (sub)term node */
   private def buildTree(node: DefaultMutableTreeNode, parent: ContentPath, t: objects.Obj, defaultReg: SourceRegion) {
      val label = t match {
         case OMV(n) => "var " + n.toString
         case OMS(p) => "con " + p.last
         case OMSemiFormal(_) => "unparsed"
         case v: VarDecl => "Var " + v.name
         case _ => t.role.toString
      }
      val reg = getRegion(t) getOrElse SourceRegion(defaultReg.start,defaultReg.start)
      val child = new DefaultMutableTreeNode(new MMTTermAsset(parent, t.head, label, reg))
      node.add(child)
      val objComponents : List[Obj] = t.components mapPartial {
         case o: Obj => Some(o)
         case _ => None
      } 
      objComponents foreach {c => buildTree(child, parent, c, reg)}
   }
   
   def parse(buffer: Buffer, errorSource: DefaultErrorSource) : SideKickParsedData = {
      val path = File(buffer.getPath)
      val tree = new SideKickParsedData(path.toJava.getName)
      val root = tree.root
      try {
         val (doc,errors) = controller.read(path, None)
         val errors2 = controller.checker(doc)
         // add narrative structure of doc to outline tree
         buildTree(root, doc)
         // register errors with ErrorList plugin
         (errors ::: errors2) foreach {
            case s: SourceError =>
               //generated by StructureParser or TextReader
               val tp = if (s.warning) ErrorSource.WARNING else ErrorSource.ERROR
               val pos = s.ref.region.start
               val file = controller.backend.resolveLogical(s.ref.container) match {
                  case Some((a,p)) => (a.sourceDir / p).toString
                  case None => s.ref.container.toString
               }
               val error = new DefaultErrorSource.DefaultError(errorSource, tp, file, pos.line, pos.column, pos.column + 1, s.mainMessage)
               s.extraMessages foreach {m => error.addExtraMessage(m)}
               errorSource.addError(error)
            case e: Invalid =>
               //generated by StructureChecker
               val cause = e match {
                  case e: InvalidObject => e.obj
                  case e: InvalidElement => e.elem
               }
               val ref = SourceRef.get(cause) getOrElse SourceRef(utils.FileURI(path), SourceRegion(SourcePosition(0,0,0), SourcePosition(0,0,0))) 
               val reg = ref.region
               val error = new DefaultErrorSource.DefaultError(errorSource, ErrorSource.ERROR, buffer.getPath,
                     reg.start.line, reg.start.column, reg.start.column + reg.length, e.getMessage)
               errorSource.addError(error)
            case e: Error =>
               // other error, should not happen
               val error = new DefaultErrorSource.DefaultError(
                   errorSource, ErrorSource.ERROR, path.toString, 0, 0, 1, e.getMessage
               )
               errorSource.addError(error)
         }
      } catch {case e: java.lang.Throwable =>
         // other error, e.g., by the get methods in buildTree
         val error = new DefaultErrorSource.DefaultError(errorSource, ErrorSource.ERROR, path.toString, 0,0,0, e.getMessage)
         e.getStackTrace foreach {m => error.addExtraMessage(m.toString)}
         errorSource.addError(error)
         log(e.getMessage)
      }
      tree
   }
   // override def stop() 
   // override def getParseTriggers : String = ""

   override def supportsCompletion = true
   override def canCompleteAnywhere = true
   // override def getInstantCompletionTriggers : String = ""
   override def complete(editPane: EditPane, caret : Int) : SideKickCompletion = {
      val textArea = editPane.getTextArea
      val view = editPane.getView
      val pd = SideKickParsedData.getParsedData(view)
      val asset = pd.getAssetAtOffset(caret).asInstanceOf[MMTAsset]
      asset.getScope match {
        case Some(a) =>
           val p = textArea.getCaretPosition
           var l = 0 // number of character to the left of the caret that are id characters
           while (l < p && MMTPlugin.isIDChar(textArea.getText(p - l - 1,1)(0))) {l = l + 1}
           val partialName = textArea.getText(p - l, l)
           val compls = Names.resolve(a, Nil, partialName)(controller.localLookup)
           new MyCompletion(view, partialName, compls.map(_.completion.toPath))
        case None => new MyCompletion(view, "", Nil)
      }
      
   }
}