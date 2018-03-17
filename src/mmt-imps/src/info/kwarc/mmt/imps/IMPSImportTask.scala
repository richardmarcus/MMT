package info.kwarc.mmt.imps

import info.kwarc.mmt.api.archives._
import info.kwarc.mmt.api.checking.{Checker, CheckingEnvironment, MMTStructureChecker, RelationHandler}
import info.kwarc.mmt.api.documents._
import info.kwarc.mmt.api.{LocalName, _}
import info.kwarc.mmt.api.frontend._
import info.kwarc.mmt.api.metadata.MetaDatum
import info.kwarc.mmt.api.modules.{DeclaredTheory, DeclaredView}
import info.kwarc.mmt.api.objects._
import info.kwarc.mmt.api.opaque.{OpaqueText, StringFragment}
import info.kwarc.mmt.api.parser.SourceRef
import info.kwarc.mmt.api.symbols.Declaration
import info.kwarc.mmt.api.symbols.PlainInclude
import info.kwarc.mmt.imps.Usage.Usage
import info.kwarc.mmt.imps.impsMathParser.{freshVar, makeSEXPFormula}
import info.kwarc.mmt.lf.{Apply, ApplySpine}
import utils._

/* REMINDER:

DPath = Namespace
LocalName(s : String)
Dpath ? LocalName = MPath (theory/view)
MPath ? LocalName = GlobalName (declarations)

namespace http://imps.blubb

theory Booleans =
 constant true%val <- http://imps.blubb?Booleans?true%val
 */

class IMPSImportTask(val controller: Controller, bt: BuildTask, index: Document => Unit, tState : TranslationState)
  extends Logger with MMTTask
{
	          def logPrefix : String = "imps-omdoc"
	protected def report    : Report = controller.report

  val rootdpath : DPath                = DPath(URI.http colon "imps.mcmaster.ca") /* arbitrary, but seemed fitting */

  /* Source References. Methods exceedingly small, but look nicer than doing this everywhere directly */
  def doSourceRef(t : Term, s : SourceRef)        : Unit = { SourceRef.update(t, s) }
  def doSourceRef(d : Declaration, s : SourceRef) : Unit = { SourceRef.update(d, s) }

  /* Add metadata from usages element */
  def doUsages(d : Declaration, usages : List[Usage]) : Unit =
  {
    for (usage <- usages)
    {
      // using rootdpath and not IMPSTheory.rootdpath because this is IMPS, not LUTINS
      val metadata_verb   : GlobalName = rootdpath ? d.name ? LocalName("usage")
      val metadata_object : Obj        = OMS(rootdpath ? d.name ? usage.toString)
      d.metadata.add(new MetaDatum(metadata_verb, metadata_object))
    }
  }

  /* Add more generic metadata, meant to be used for short strings, not proofs
   * Might be rewritten, when we have cleverer solutions for MetaData */
  def doMetaData(d : Declaration, metaVerb : String, metaObject : String) : Unit =
  {
    val mv : GlobalName =     rootdpath ? d.name ? LocalName(metaVerb)
    val mo : Obj        = OMS(rootdpath ? d.name ? metaObject)

    d.metadata.add(new MetaDatum(mv,mo))
  }

  def doName(s : String) : LocalName = LocalName(s)

	def doDocument(es : Exp, uri : URI) : BuildResult =
	{
    val doc = new Document(bt.narrationDPath, true)
    controller.add(doc)

    var excps : List[Exception] = Nil

    for (exp <- es.children)
    {
      exp match
      {
        /* Translating Theories to MMT */
        case t@(Theory(_,_,_,_,_,_))       => try
        {
          if (!tState.theories_raw.contains(t)) { doTheory(t, bt.narrationDPath) }
        } catch {
          case e : IMPSDependencyException => { println(" > ... fail. Add to stack: " +  e.getMessage ) ; excps = excps.::(e) }
        }
        // Languages are processed in context of theories using them, not by themselves
        case l@(Language(_,_,_,_,_,_,_,_)) => {
          if (!tState.languages.contains(l)) { println(" > adding language " + l.name) ; tState.languages = tState.languages :+ l }
        }
        // If it's none of these, fall back to doDeclaration
        case _                             => doDeclaration(exp)
      }
    }

    if (!excps.isEmpty) { throw excps.head }

    // Run Checker (to resolve unknowns, etc)
    // Set to true to run
    val typecheck : Boolean = true

    if (typecheck)
    {
      log("Checking:")
      logGroup
      {
        val checker = controller.extman.get(classOf[Checker], "mmt").getOrElse {
          throw GeneralError("no checker found")
        }.asInstanceOf[MMTStructureChecker]
        tState.theories_decl foreach { p =>
          val ce = new CheckingEnvironment(new ErrorLogger(report), RelationHandler.ignore, this)
          checker.apply(p)(ce)
        }
      }
    }

    index(doc)
    BuildSuccess(Nil,Nil)
	}

  def doTheory (t : Theory, dPath: DPath) : Unit =
  {
    val nu_theory = new DeclaredTheory(dPath,
                                       LocalName(t.name),
                                       Some(IMPSTheory.lutinsPath),
                                       modules.Theory.noParams,
                                       modules.Theory.noBase)

    println(" > trying to add theory " + t.name)

    val mref : MRef = MRef(dPath,nu_theory.path)
    controller.add(nu_theory)
    controller.add(mref)

    def thy_reset() : Unit = {
      controller.delete(nu_theory.path)
      controller.delete(mref.path)
    }

    /* Translate language of the theory */
    var l : Option[Language] = None

    // Build correct union of languages
    if (t.lang.isDefined) {
      if (!tState.languages.exists(la => la.name.toLowerCase == t.lang.get.lang.toLowerCase)) { thy_reset() ; throw new IMPSDependencyException("required language " + t.lang.get.lang + " not found") }
      l = tState.languages.find(la => la.name.toLowerCase == t.lang.get.lang.toLowerCase)
    }

    if (t.cmpntthrs.isDefined)
    {
      /* For each component theory, take its language (if there is one) */
      for (comp_theory <- t.cmpntthrs.get.lst)
      {
        if (!tState.theories_raw.exists(t => t.name.toLowerCase == comp_theory.toLowerCase)) { thy_reset() ; throw new IMPSDependencyException("required co-theory " + comp_theory.toLowerCase + " not found") }

        /* Add Include */
        val component = tState.theories_decl.find(p => p.name.toString.toLowerCase == comp_theory.toLowerCase)
        assert(component.isDefined)
        controller add PlainInclude.apply(component.get.path,nu_theory.path)

        /* Union Languages */
        val t_index : Theory = tState.theories_raw.find(t => t.name.toLowerCase == comp_theory.toLowerCase).get

        if (t_index.lang.isDefined)
        {
          assert(tState.languages.exists(la => la.name.toLowerCase == t_index.lang.get.lang.toLowerCase))
          val l_prime: Language = tState.languages.find(la => la.name.toLowerCase == t_index.lang.get.lang.toLowerCase).get

          if (l.isDefined) {
            l = Some(l.get.union(l_prime))
          } else {
            l = Some(l_prime)
          }
        }
      }
    }

    // Actually translate resulting language
    if (l.isDefined) { doLanguage(l.get, nu_theory) }

    /* Translate all axioms, if there are any */
    if (t.axioms.isDefined)
    {
      var axiom_count : Int = -1
      for (ax <- t.axioms.get.axs)
      {
        val mth : Term = tState.bindUnknowns(IMPSTheory.Thm(doMathExp(ax.formula, nu_theory, Nil)))
        val name : String = if (ax.name.isDefined) { ax.name.get }
        else { axiom_count += 1 ; t.name + "_unnamed_axiom" + axiom_count.toString }

        val assumption = symbols.Constant(nu_theory.toTerm,doName(name),Nil,Some(mth),None,Some("Assumption"))

        if (ax.usgs.isDefined) { doUsages(assumption,ax.usgs.get) }
        if (ax.src.isDefined) { doSourceRef(assumption,ax.src.get) }
        controller.add(assumption)
      }
    }

    /* All constants here per distinction element are
       axiomatically distinct from each other */
    if (t.dstnct.isDefined)
    {
      var dist_count : Int = 0

      for (dist : List[String] <- t.dstnct.get.lst) /* Reminder: t.dstnct.get.lst : List[List[String]] */
      {
        for (c1 : String <- dist)
        {
          for (c2 : String <- dist.filter(e => e != c1))
          {
            /* Assert the two constants to be distinct exist in the theory */
            assert(nu_theory.getDeclarations.exists(d => d.name == LocalName(c1)))
            assert(nu_theory.getDeclarations.exists(d => d.name == LocalName(c2)))

            /* add axiom that they are indeed distinct */
            val g1 : GlobalName = nu_theory.getDeclarations.find(d => d.name == LocalName(c1)).get.path
            val g2 : GlobalName = nu_theory.getDeclarations.find(d => d.name == LocalName(c2)).get.path

            // val dist_formula : IMPSMathExp = IMPSNegation(IMPSEquals(IMPSSymbolRef(g1), IMPSSymbolRef(g2)))
            val dist_formula : IMPSMathExp = IMPSNegation(IMPSEquals(IMPSMathSymbol(c1), IMPSMathSymbol(c2)))
            val mth          : Term = tState.bindUnknowns(IMPSTheory.Thm(doMathExp(dist_formula, nu_theory, Nil)))
            val name         : String = t.name + "_distinction_axiom_" + dist_count.toString

            dist_count += 1

            val dist_assumption = symbols.Constant(nu_theory.toTerm,doName(name),Nil,Some(mth),None,Some("Assumption"))
            if (t.dstnct.get.src.isDefined) { doSourceRef(dist_assumption, t.dstnct.get.src.get) }
            controller.add(dist_assumption)
          }
        }
      }
    }

    println(" > actually adding theory " + t.name)

    tState.theories_decl = tState.theories_decl :+ nu_theory
    tState.theories_raw  = tState.theories_raw  :+ t

  }

  def doLanguage(l : Language, t : DeclaredTheory) : Unit =
  {
    def doLanguageOrTheory(target : String, t : DeclaredTheory) : Unit =
    {
      val exists_theory   : Boolean = tState.theories_raw.exists(p => p.name.toLowerCase == target.toLowerCase)
      val exists_language : Boolean = tState.languages.exists(p => p.name.toLowerCase == target.toLowerCase)

      if (!(exists_language || exists_theory))
      {
        throw new IMPSDependencyException("neither required theory nor language named " + target.toLowerCase + " not found")
      }

      if (exists_language)
      {
        doLanguage(tState.languages.find(p => p.name.toLowerCase == target.toLowerCase).get, t)
      }
      else if (exists_theory)
      {
        val argt : ArgumentLanguage = tState.theories_raw.find(p => p.name.toLowerCase == target.toLowerCase).get.lang.get
        assert(tState.languages.exists(p => p.name.toLowerCase == argt.lang.toLowerCase))
        doLanguage(tState.languages.find(p => p.name.toLowerCase == argt.lang.toLowerCase).get, t)
      }
    }

    if (l.embedlang.isDefined) {
      doLanguageOrTheory(l.embedlang.get.name,t)
    }

    if (l.embedlangs.isDefined)
    {
      for (l_embed <- l.embedlangs.get.names) {
        doLanguageOrTheory(l_embed, t)
      }
    }

    if (l.bstps.isDefined)
    {
      for (baseType : String <- l.bstps.get.tps)
      {
        val tp : Term = IMPSTheory.Sort(OMS(IMPSTheory.lutinsIndType))
        val basetype = symbols.Constant(t.toTerm, doName(baseType), Nil, Some(tp), None, Some("BaseType"))
        if (l.bstps.get.src.isDefined) { doSourceRef(basetype, l.bstps.get.src.get) }
        controller add basetype
      }
    }

    if (l.srts.isDefined)
    {
      /* introduce all sorts with their respective enclosing sorts */
      for (spec : (IMPSSort, IMPSSort) <- l.srts.get.lst)
        { doSubsort(spec._1, spec._2, t, l.srts.get.src) }
    }

    if (l.extens.isDefined)
    {
      for (tal : TypeSortAList <- l.extens.get.lst)
      {
        // TODO: Can this be translated into something non-opaque?
        //       See IMPS manual, pgs. 172, 173
        val opaque = new OpaqueText(t.path.toDPath, OpaqueText.defaultFormat, StringFragment(tal.toString))
        controller.add(opaque)
      }
    }

    if (l.cnstnts.isDefined)
    {
      for (pair : (String, IMPSSort) <- l.cnstnts.get.lst)
      {
        val mth_tp : Term = doSort(pair._2, t)
        val l_const = symbols.Constant(t.toTerm,doName(pair._1),Nil,Some(mth_tp),None,Some("Constant"))
        if (l.cnstnts.get.src.isDefined) { doSourceRef(l_const,l.cnstnts.get.src.get) }
        controller add l_const
      }
    }
  }

  def doDeclaration (d : LispExp) : Unit =
  {
    // set this to true for helpful debug output
    val debug : Boolean = false
    if (debug)
    {
      println("\n>>>>> Call to doDecl for the following expression:\n")
      println(d.toString)
      for (thy <- tState.theories_decl)
      {
        println("\n<<<<< Theory " + thy.name + " contains the following declarations:")
        for(d <- thy.getDeclarations)
        { println("~~~ " + d.name.toString) }
      }
    }

    d match {
      case Heralding(md, src) => {
        println(" > Dropping (herald ...)")
      }
      case AtomicSort(name, defstring, theory, usages, witness, src, sort) =>

        val ln: LocalName = LocalName(theory.thy.toLowerCase())

        if (!tState.theories_decl.exists(t => t.name.toString.toLowerCase == ln.toString)) {
          throw new IMPSDependencyException("required theory " + ln + " for atomic sort not found")
        }
        val parent: DeclaredTheory = tState.theories_decl.find(dt => dt.name.toString.toLowerCase == ln.toString).get

        val definition: Term = tState.bindUnknowns(doMathExp(defstring, parent, Nil))
        val enclosing: Term = doSort(sort, parent)
        val nu_atomicSort = symbols.Constant(parent.toTerm, doName(name), Nil, Some(enclosing), Some(definition), Some("AtomicSort"))

        /* Add available MetaData */
        if (witness.isDefined) {
          doMetaData(nu_atomicSort, "witness", witness.get.witness.toString)
        }
        if (usages.isDefined) {
          doUsages(nu_atomicSort, usages.get.usgs)
        }

        if (src.isDefined) { doSourceRef(nu_atomicSort, src.get) }

        println(" > Adding atomic sort: " + name + " (enclosed by " + sort.toString + ")")
        controller add nu_atomicSort

      case Constant(name, definition, theory, sort, usages, src) =>

        val ln: LocalName = LocalName(theory.thy.toLowerCase())
        if (!tState.theories_decl.exists(t => t.name.toString.toLowerCase == ln.toString)) {
          throw new IMPSDependencyException("required theory " + ln + " for constant not found")
        }
        val parent: DeclaredTheory = tState.theories_decl.find(dt => dt.name.toString.toLowerCase == ln.toString).get

        /* look for sort in given theory. */
        var srt: Option[Term] = None
        if (sort.isDefined) {
          println(" > Adding constant with clear sort: " + name)
          /* Theory not in scope, so we find it by hand */
          val theTheory: Option[DeclaredTheory] = tState.theories_decl.find(x => x.name == LocalName(theory.thy))
          assert(theTheory.isDefined)

          srt = Some(doSort(sort.get.sort, theTheory.get))
        }
        else {
          println(" > Adding constant with unclear sort: " + name)
          srt = None
        }

        val mth: Term = tState.bindUnknowns(doMathExp(definition, parent,Nil))
        val nu_constant = symbols.Constant(parent.toTerm, LocalName(name), Nil, srt, Some(mth), Some("Constant"))

        /* Add available MetaData */
        if (src.isDefined) { doSourceRef(nu_constant, src.get) }
        if (usages.isDefined) {
          doUsages(nu_constant, usages.get.usgs)
        }
        controller add nu_constant

      case Theorem(name, formula, lemma, reverse, theory, usages, transport, macete, homeTheory, maybeProof, src) => {

        val ln: LocalName = doName(theory.thy.toLowerCase)
        if (!tState.theories_decl.exists(t => t.name.toString.toLowerCase == ln.toString)) {
          throw new IMPSDependencyException("required theory " + ln + " for theorem not found")
        }
        val parent: DeclaredTheory = tState.theories_decl.find(dt => dt.name.toString.toLowerCase == ln.toString).get

        val mth: Term = tState.bindUnknowns(IMPSTheory.Thm(doMathExp(formula, parent,Nil)))
        val nu_theorem = symbols.Constant(parent.toTerm, doName(name), Nil, Some(mth), None, Some("Theorem"))
        //                                                                              ^-- proof goes here!

        /* Add available MetaData */
        if (usages.isDefined) {
          doUsages(nu_theorem, usages.get.usgs)
        }
        if (transport.isDefined) {
          doMetaData(nu_theorem, "translation", transport.get.trans)
        }
        if (macete.isDefined) {
          doMetaData(nu_theorem, "macete", macete.get.macete)
        }
        if (homeTheory.isDefined) {
          doMetaData(nu_theorem, "homeTheory", homeTheory.get.hmthy)
        }

        if (lemma) {
          doMetaData(nu_theorem, "lemma", "present")
        } else {
          doMetaData(nu_theorem, "lemma", "absent")
        }
        if (reverse) {
          doMetaData(nu_theorem, "reverse", "present")
        } else {
          doMetaData(nu_theorem, "reverse", "absent")
        }

        if (src.isDefined) { doSourceRef(nu_theorem, src.get) }
        controller add nu_theorem

        if (maybeProof.isDefined) {
          /* opaque proofs are beetter than no proofs */
          val proof_name: StringFragment = StringFragment("Opaque proof of theorem " + name)
          val proof_text: StringFragment = StringFragment(maybeProof.get.prf.toString)

          val opaque = new OpaqueText(parent.path.toDPath, OpaqueText.defaultFormat, StringFragment(proof_name + "\n" + proof_text))
          controller add opaque
        }
      }
      case Translation(name, force, forceQL, dontEnrich, sourcet, targett, assumptions, fixed, sortpairs, constpairs, coretrans, theintcheck, src) => {

        val ln : LocalName = doName(name)

        println(" > translating Translation " + name)

        // Source and Target need to be defined!
        assert(tState.theories_decl.exists(t => t.name.toString.toLowerCase == doName(sourcet.thy).toString.toLowerCase))
        val source_thy : Term = tState.theories_decl.find(t => t.name.toString.toLowerCase == doName(sourcet.thy).toString.toLowerCase).get.toTerm

        assert(tState.theories_decl.exists(t => t.name.toString.toLowerCase == doName(targett.thy).toString.toLowerCase))
        val target_thy : Term = tState.theories_decl.find(t => t.name.toString.toLowerCase == doName(targett.thy).toString.toLowerCase).get.toTerm

        val nu_view = new DeclaredView(bt.narrationDPath, ln, source_thy, target_thy, false)

        if (force) {
          val mv : GlobalName =     rootdpath ? name ? LocalName("force")
          val mo : Obj        = OMS(rootdpath ? name ? "present")
          nu_view.metadata.add(new MetaDatum(mv,mo))
        } else {
          val mv : GlobalName =     rootdpath ? name ? LocalName("force")
          val mo : Obj        = OMS(rootdpath ? name ? "absent")
          nu_view.metadata.add(new MetaDatum(mv,mo))
        }

        if (forceQL) {
          val mv : GlobalName =     rootdpath ? name ? LocalName("force-under-quick-load")
          val mo : Obj        = OMS(rootdpath ? name ? "present")
          nu_view.metadata.add(new MetaDatum(mv,mo))
        } else {
          val mv : GlobalName =     rootdpath ? name ? LocalName("force-under-quick-load")
          val mo : Obj        = OMS(rootdpath ? name ? "absent")
          nu_view.metadata.add(new MetaDatum(mv,mo))
        }

        if (dontEnrich) {
          val mv : GlobalName =     rootdpath ? name ? LocalName("dont-enrich")
          val mo : Obj        = OMS(rootdpath ? name ? "present")
          nu_view.metadata.add(new MetaDatum(mv,mo))
        } else {
          val mv : GlobalName =     rootdpath ? name ? LocalName("dont-enrich")
          val mo : Obj        = OMS(rootdpath ? name ? "absent")
          nu_view.metadata.add(new MetaDatum(mv,mo))
        }

        println(" > Adding translation " + name + " (not complete yet)")

        if (src.isDefined) { doSourceRef(nu_view.toTerm,src.get) }
        controller add nu_view

      }
      case SchematicMacete(_, _, thy, _, _, _) => {

        val ln: LocalName = LocalName(thy.thy.toLowerCase)
        if (!tState.theories_decl.exists(t => t.name.toString.toLowerCase == ln.toString)) {
          throw new IMPSDependencyException("required theory " + ln + " for schematic macete not found")
        }
        val parent: DeclaredTheory = tState.theories_decl.find(dt => dt.name.toString.toLowerCase == ln.toString).get

        // Macetes are added as opaque (for now?)
        val opaque = new OpaqueText(parent.path.toDPath, OpaqueText.defaultFormat, StringFragment(d.toString))

        /* Opaque Text doesn't have metadata, apparently, so we don't add the src */

        controller add opaque
      }
      case QuasiConstructor(name,exprstr,arglang,fixed,src) =>
      {
        // Quasi-Constructors needed to be built in because they're not parseable

        assert(tState.languages.exists(p => p.name.toLowerCase == arglang.lang.toLowerCase)
           ||  tState.theories_raw.exists(p => p.name.toLowerCase == arglang.lang.toLowerCase))

        val parent: DeclaredTheory = if (!tState.languages.exists(p => p.name.toLowerCase == arglang.lang.toLowerCase))
        {
          tState.theories_decl.find(dt => dt.name.toString.toLowerCase == LocalName(arglang.lang).toString.toLowerCase).get
        }
        else
        {
          // Find correct theory for language.
          val ln: LocalName = LocalName(tState.theories_raw.find(t => t.lang.get.lang.toLowerCase == arglang.lang.toLowerCase).head.name)
          if (!tState.theories_decl.exists(t => t.name.toString.toLowerCase == ln.toString.toLowerCase)) {
            throw new IMPSDependencyException("required theory " + ln + " for quasi-constructor not found")
          }

          tState.theories_decl.find(dt => dt.name.toString.toLowerCase == ln.toString.toLowerCase).get
        }

        val opaque = new OpaqueText(parent.path.toDPath, OpaqueText.defaultFormat, StringFragment(d.toString))

        controller add opaque
      }
      case _ => {
        println(" > Error: Unknown decl encountered, not translated!")
        println(" > " + d.toString)
      }
    }
  }

  def findKind(s : IMPSSort) : Term =
  {
    s match {
      case IMPSAtomSort("ind")      => OMS(IMPSTheory.lutinsIndType)
      case IMPSAtomSort("prop")     => OMS(IMPSTheory.lutinsPropType)
      case IMPSAtomSort("bool")     => OMS(IMPSTheory.lutinsPropType)
      case IMPSAtomSort("unit%sort")
         | IMPSAtomSort("unitsort") => OMS(IMPSTheory.lutinsIndType)
      case IMPSAtomSort(_)          => OMS(IMPSTheory.lutinsIndType)
      case IMPSBinaryFunSort(s1,s2) => IMPSTheory.FunType(findKind(s1),findKind(s2))
      case IMPSSetSort(s)           => findKind(IMPSBinaryFunSort(s,IMPSAtomSort("ind")))
      case _ => ??? // This should never happen, always call curry first!
    }
  }

  def matchSort(e : IMPSSort, t : DeclaredTheory) : Term =
  {
    e match {
      case IMPSAtomSort("ind")  => OMS(IMPSTheory.lutinsPath ? "ind")
      case IMPSAtomSort("prop") => OMS(IMPSTheory.lutinsPath ? "bool")
      case IMPSAtomSort("bool") => OMS(IMPSTheory.lutinsPath ? "bool")
      case IMPSAtomSort("unit%sort")
         | IMPSAtomSort("unitsort") => OMS(IMPSTheory.lutinsPath ? "unitsort")
      case IMPSAtomSort(srt) => OMS(t.path ? srt)
      case IMPSBinaryFunSort(s1, s2) =>
      {
        val tpA: Term = findKind(s1)
        val tpB: Term = findKind(s2)

        val sortA: Term = matchSort(s1, t)
        val sortB: Term = matchSort(s2, t)

        IMPSTheory.FunSort(tpA, tpB, sortA, sortB)
      }
      case IMPSSetSort(s) => {

        val tp  : Term = findKind(s)
        val srt : Term = matchSort(s,t)

        IMPSTheory.Sets(tp,srt)
      }
    }
  }

  /* Walks sort structure, currying all NaryFunSorts into BinaryFunSorts */
  def curry(srt : IMPSSort) : IMPSSort =
  {
    srt match
    {
      case IMPSAtomSort(_)                 => srt // don't change atomic sorts
      case IMPSBinaryFunSort(sort1, sort2) => IMPSBinaryFunSort(curry(sort1),curry(sort2))
      case IMPSNaryFunSort(sorts)          => {
        if (sorts.length == 2) {
          IMPSBinaryFunSort(curry(sorts(0)), curry(sorts(1)))
        } else {
          IMPSBinaryFunSort(curry(sorts(0)), curry(IMPSNaryFunSort(sorts.tail)))
        }
      }
      case IMPSSetSort(st) => IMPSSetSort(curry(st))
    }
  }

  def doSort(d : IMPSSort, t : DeclaredTheory) : Term =
  {
    val d_prime : IMPSSort = curry(d)
    val tp      : Term     = findKind(d_prime)

    IMPSTheory.exp(tp, matchSort(d_prime,t))
  }

  /* Introduces a sort to a theory and also assigns the enclosing sort to it. */
  def doSubsort(subsort : IMPSSort, supersort : IMPSSort, thy : DeclaredTheory, src : Option[SourceRef]) : Unit =
  {
    /* enclosing sort should already be defined */
    println(" > Adding sort: " + subsort.toString + ", enclosed by " + supersort.toString)

    val opt_ind   : Option[Term] = Some(Apply(OMS(IMPSTheory.lutinsPath ? LocalName("sort")), OMS(IMPSTheory.lutinsIndType)))
    val jdgmtname : LocalName    = LocalName(subsort.toString + "_sub_" + supersort.toString)

    val foo       : Term = matchSort(subsort,thy)
    val bar       : Term = matchSort(supersort,thy)
    val baz       : Term = findKind(supersort)

    val subs      : Term = ApplySpine(OMS(IMPSTheory.lutinsPath ? LocalName("subsort")), baz, foo, bar)

    val jdgmttp   : Option[Term] = Some(IMPSTheory.Thm(subs))

    /* ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ */

    val typing    : Declaration  = symbols.Constant(thy.toTerm,LocalName(subsort.toString),Nil,opt_ind,None,Some("Subsort_1"))
    val judgement : Declaration  = symbols.Constant(thy.toTerm,jdgmtname,Nil,jdgmttp,None,Some("Subsort_2"))

    if (src.isDefined)
    {
      doSourceRef(typing, src.get)
      doSourceRef(judgement, src.get)
    }

    controller add typing
    controller add judgement
  }

  /* Translate IMPS Math Expressions to Terms */
  def doMathExp(d : IMPSMathExp, thy : DeclaredTheory, cntxt : List[(IMPSVar,IMPSSort)]) : Term =
  {
    d match
    {
      case IMPSVar(v)             => if (cntxt.map(_._1).contains(d)) { OMV(v) } else {
        println(" | Switching from Var to MathSymbol: " + v + " ∉ {" + cntxt.toString() + "}")
        doMathExp(IMPSMathSymbol(v),thy,cntxt)
      }

      case IMPSMathSymbol("an%individual") => { OMS(IMPSTheory.lutinsPath ? "anIndividual") }
      case IMPSMathSymbol(s)      => OMS(thy.path ? LocalName(s))

      case IMPSIndividual()       => OMS(IMPSTheory.lutinsPath ? "anIndividual")
      case IMPSTruth()            => OMS(IMPSTheory.lutinsPath ? "thetrue")
      case IMPSFalsehood()        => OMS(IMPSTheory.lutinsPath ? "thefalse")

      case IMPSNegation(p)        => IMPSTheory.Negation(doMathExp(p,thy,cntxt))
      case IMPSIf(p,t1,t2)        => IMPSTheory.If(tState.addUnknown(), tState.addUnknown(), doMathExp(p,thy,cntxt), doMathExp(t1,thy,cntxt), doMathExp(t2,thy,cntxt))
      case IMPSIff(p, q)          => IMPSTheory.Iff(doMathExp(p,thy,cntxt), doMathExp(q,thy,cntxt))
      case IMPSIfForm(p,q,r)      => IMPSTheory.If_Form(doMathExp(p,thy,cntxt), doMathExp(q,thy,cntxt), doMathExp(r,thy,cntxt))
      case IMPSEquals(p,q)        => {
        var a     : Term = null
        var alpha : Term = null
        var beta  : Term = null

        p match {
          case IMPSVar(_) => {
            if (cntxt.map(_._1).contains(p)) {
              val theSort = cntxt.find(k => k._1 == p).get._2
              alpha = matchSort(theSort,thy)
              a = findKind(theSort)
            }
          }
          case _          => ()
        }

        q match {
          case IMPSVar(_) => {
            if (cntxt.map(_._1).contains(q)) {
              val theSort = cntxt.find(k => k._1 == q).get._2
              beta = matchSort(theSort,thy)
              if (a == null) { a = findKind(theSort) } else { assert(a == findKind(theSort)) }
            }
          }
          case _          => ()
        }

        if (alpha == null) { alpha = tState.addUnknown() }
        if (beta  == null) { beta  = tState.addUnknown() }
        if (a     == null) { a     = tState.addUnknown() }

        IMPSTheory.Equals(a,alpha,beta,doMathExp(p,thy,cntxt),doMathExp(q,thy,cntxt))
      }
      case IMPSDisjunction(ls)    => IMPSTheory.Or(ls map (x => doMathExp(x,thy,cntxt)))
      case IMPSConjunction(ls)    => IMPSTheory.And(ls map (x => doMathExp(x,thy,cntxt)))
      case q@IMPSLambda(vs,_)     => doIMPSLambda(curryIMPSlambda(q), thy, cntxt ::: vs)
      case q@IMPSForAll(vs,_)     => doIMPSForall(curryIMPSforall(q), thy, cntxt ::: vs)
      case q@IMPSForSome(vs, r)   => doIMPSForsome(curryIMPSforsome(q),thy, cntxt ::: vs)
      case IMPSImplication(p,q)   => IMPSTheory.Implies(doMathExp(p,thy,cntxt), doMathExp(q,thy,cntxt))
      case IMPSApply(f,ts)        =>
      {
        assert(ts.nonEmpty)
        // Wheee, manual currying!
        if (ts.length == 1)
        {
          var alpha : Term = null
          var beta  : Term = null
          var gamma : Term = null

          var a     : Term = null
          var b     : Term = null

          f match {
            case IMPSVar(_) => {
              if (cntxt.map(_._1).contains(f)) {
                val theSort = cntxt.find(p => p._1 == f).get._2
                theSort match {
                  case IMPSBinaryFunSort(s1,s2) => {
                    alpha = matchSort(s1,thy)
                    beta  = matchSort(s2,thy)
                    a     = findKind(s1)
                    b     = findKind(s2)
                  }
                  case IMPSSetSort(s1) => {
                    alpha = matchSort(s1,thy)
                    beta  = matchSort(IMPSAtomSort("ind"),thy)
                    a     = findKind(s1)
                    b     = findKind(IMPSAtomSort("ind"))
                  }
                  case _ => println(" > theSort = " + theSort.toString) ; assert(false)
                }
              }
            }
            case _ => ()
          }

          ts.head match {
            case IMPSVar(_) => {
              if (cntxt.map(_._1).contains(ts.head)) {
                val theSort = cntxt.find(p => p._1 == ts.head).get._2
                gamma = matchSort(theSort,thy)
                if (a == null) { a = findKind(theSort) } else { assert(a == findKind(theSort)) }
              }
            }
            case _          => ()
          }

          if (alpha == null) { alpha = tState.addUnknown() }
          if (beta  == null) { beta  = tState.addUnknown() }
          if (gamma == null) { gamma = tState.addUnknown() }

          if (a == null) { a = tState.addUnknown() }
          if (b == null) { b = tState.addUnknown() }

          IMPSTheory.IMPSApply(a, b, alpha, gamma, beta, doMathExp(f,thy,cntxt), doMathExp(ts.head,thy,cntxt))
        }
        else
        {
          val inner = IMPSApply(f,List(ts.head))
          doMathExp(IMPSApply(inner,ts.tail),thy,cntxt)
        }
      }
      case IMPSIota(v1,s1,p)      => IMPSTheory.Iota(LocalName(v1.v), doSort(s1,thy), doMathExp(p,thy,cntxt ::: List((v1,s1))))
      case IMPSIotaP(v1,s1,p)     => IMPSTheory.IotaP(LocalName(v1.v), doSort(s1,thy), doMathExp(p,thy,cntxt ::: List((v1,s1))))
      case IMPSIsDefined(r)       => IMPSTheory.IsDefined(tState.addUnknown(), tState.addUnknown(), doMathExp(r,thy,cntxt))
      case IMPSIsDefinedIn(r,s)   => IMPSTheory.IsDefinedIn(findKind(s), tState.addUnknown(), doMathExp(r,thy,cntxt), matchSort(s,thy))
      case IMPSUndefined(s)       => IMPSTheory.Undefined(findKind(s), matchSort(s,thy))

      case IMPSTotal(f,bs)        => IMPSTheory.Total(doMathExp(f,thy,cntxt),bs.map(b => doSort(b,thy)))
      case IMPSNonVacuous(p)      => IMPSTheory.Nonvacuous(doMathExp(p,thy,cntxt))
      case IMPSQuasiEquals(p,q)   => IMPSTheory.Quasiequals(doMathExp(p,thy,cntxt), doMathExp(q,thy,cntxt))

      case IMPSQCPred2Indicator(_)
         | IMPSQCSort2Indicator(_)
         | IMPSQCIn(_,_)
         | IMPSQCSubsetEQ(_,_)
         | IMPSQCSubset(_,_)      => doMathExp(removeQCs(d,Nil),thy,cntxt)
    }
  }

  def curryIMPSlambda(l : IMPSLambda) : IMPSLambda =
  {
    assert(l.vs.nonEmpty)
    if (l.vs.length == 1) { l }
    else {
      IMPSLambda(List(l.vs.head),curryIMPSlambda(IMPSLambda(l.vs.tail,l.t)))
    }
  }

  def doIMPSLambda(lambda : IMPSLambda, thy : DeclaredTheory, cntxt : List[(IMPSVar,IMPSSort)]) : Term =
  {
    // Always call curried
    assert(lambda.vs.length == 1)

    val thisVar     : LocalName = LocalName(lambda.vs.head._1.v)
    val thisSrt     : IMPSSort  = lambda.vs.head._2
    val expSortTerm : Term      = doSort(thisSrt,thy)     // <-- this is "exp whateversort"
    val target      : Term      = doMathExp(lambda.t,thy,cntxt)
    val body        : Term      = info.kwarc.mmt.lf.Lambda(thisVar, expSortTerm, target)

    val jstSortTerm : Term      = matchSort(thisSrt,thy)
    //                                ^--------------------------v--These are just the sort
    IMPSTheory.Lambda(findKind(thisSrt), tState.addUnknown(), jstSortTerm, tState.addUnknown(), body)
  }

  def curryIMPSforsome(f : IMPSForSome) : IMPSForSome =
  {
    assert(f.vs.nonEmpty)
    if (f.vs.length == 1) { f }
    else {
      IMPSForSome(List(f.vs.head),curryIMPSforsome(IMPSForSome(f.vs.tail,f.p)))
    }
  }

  def doIMPSForsome(forsome : IMPSForSome, thy : DeclaredTheory, cntxt : List[(IMPSVar,IMPSSort)]) : Term =
  {
    // Always call curried
    assert(forsome.vs.length == 1)

    val thisVar     : LocalName = LocalName(forsome.vs.head._1.v)
    val thisSrt     : IMPSSort  = forsome.vs.head._2
    val expSortTerm : Term      = doSort(thisSrt,thy)     // <-- this is "exp whateversort"
    val target      : Term      = doMathExp(forsome.p,thy,cntxt)
    val body        : Term      = info.kwarc.mmt.lf.Lambda(thisVar, expSortTerm, target)

    val jstSortTerm : Term      = matchSort(thisSrt,thy)
    //                                ^-------v-------------------These are just the sort
    IMPSTheory.Forsome(findKind(thisSrt), jstSortTerm, body)
  }

  def curryIMPSforall(f : IMPSForAll) : IMPSForAll =
  {
    assert(f.vs.nonEmpty)
    if (f.vs.length == 1) { f }
    else {
      IMPSForAll(List(f.vs.head),curryIMPSforall(IMPSForAll(f.vs.tail,f.p)))
    }
  }

  def doIMPSForall(forall : IMPSForAll, thy : DeclaredTheory, cntxt : List[(IMPSVar,IMPSSort)]) : Term =
  {
    // Always call curried
    assert(forall.vs.length == 1)

    val thisVar     : LocalName = LocalName(forall.vs.head._1.v)
    val thisSrt     : IMPSSort  = curry(forall.vs.head._2)
    val expSortTerm : Term      = doSort(thisSrt,thy)     // <-- this is "exp whateversort"
    val target      : Term      = doMathExp(forall.p,thy,cntxt)
    val body        : Term      = info.kwarc.mmt.lf.Lambda(thisVar, expSortTerm, target)

    val jstSortTerm : Term      = matchSort(thisSrt,thy)
    //                                ^-------v-------------------These are just the sort
    IMPSTheory.Forall(findKind(thisSrt), jstSortTerm, body)
  }

  def removeQCs(input : IMPSMathExp, addCs : List[IMPSMathExp]) : IMPSMathExp =
  {
    input match
    {
      case IMPSVar(_)
         | IMPSIndividual()
         | IMPSMathSymbol(_)
         | IMPSTruth()
         | IMPSFalsehood()        => input

      case IMPSNegation(p)        => IMPSNegation(removeQCs(p,addCs))
      case IMPSIf(p,t1,t2)        => IMPSIf(removeQCs(p,addCs),removeQCs(t1,addCs),removeQCs(t2,addCs))
      case IMPSIff(p, q)          => IMPSIff(removeQCs(p,addCs), removeQCs(q,addCs))
      case IMPSIfForm(p,q,r)      => IMPSIfForm(removeQCs(p,addCs),removeQCs(q,addCs),removeQCs(r,addCs))
      case IMPSEquals(a,b)        => IMPSEquals(removeQCs(a,addCs),removeQCs(b,addCs))
      case IMPSDisjunction(ls)    => IMPSDisjunction(ls.map(l => removeQCs(l,addCs)))
      case IMPSConjunction(ls)    => IMPSConjunction(ls.map(l => removeQCs(l,addCs)))
      case IMPSLambda(vs,t)       => IMPSLambda(vs,removeQCs(t,addCs))
      case IMPSForAll(vs,p)       => IMPSForAll(vs,removeQCs(p,addCs))
      case IMPSForSome(vs,r)      => IMPSForSome(vs,removeQCs(r,addCs))
      case IMPSImplication(p,q)   => IMPSImplication(removeQCs(p,addCs),removeQCs(q,addCs))
      case IMPSApply(f,ts)        => IMPSApply(removeQCs(f,addCs),ts.map(t => removeQCs(t,addCs)))
      case IMPSIota(v1,s1,p)      => IMPSIota(v1,s1,removeQCs(p,addCs))
      case IMPSIotaP(v1,s1,p)     => IMPSIotaP(v1,s1,removeQCs(p,addCs))
      case IMPSIsDefined(r)       => IMPSIsDefined(removeQCs(r,addCs))
      case IMPSIsDefinedIn(r,s)   => IMPSIsDefinedIn(removeQCs(r,addCs),s)
      case IMPSUndefined(s)       => IMPSUndefined(s)
      case IMPSTotal(f,bs)        => IMPSTotal(removeQCs(f,addCs),bs)
      case IMPSNonVacuous(p)      => IMPSNonVacuous(removeQCs(p,addCs))
      case IMPSQuasiEquals(p,q)   => IMPSQuasiEquals(removeQCs(p,addCs),removeQCs(q,addCs))

      case IMPSQCPred2Indicator(pred_u) =>
      {
        // "lambda(s:[uu,prop], lambda(x:uu, if(s(x), an%individual, ?unit%sort)))"
        val pred  : IMPSMathExp = removeQCs(pred_u,addCs)

        val s_var = (freshVar("s",List(pred)          ::: addCs), IMPSBinaryFunSort(IMPSAtomSort("uu"),IMPSAtomSort("prop")))
        val x_var = (freshVar("x",List(pred,s_var._1) ::: addCs), IMPSAtomSort("uu"))

        val appl    = IMPSApply(s_var._1,List(x_var._1))
        val target  = IMPSIf(appl,IMPSIndividual(),IMPSUndefined(IMPSAtomSort("unitsort")))
        val resolve = IMPSApply(IMPSLambda(List(s_var), IMPSLambda(List(x_var),target)), List(pred))

        resolve
      }

      case IMPSQCSort2Indicator(sort_u) =>
      {
        // "lambda(e:uu, lambda(x:uu, an%individual))"
        val sort  : IMPSMathExp = removeQCs(sort_u,addCs)

        val y_var = (freshVar("x",List(sort)          ::: addCs), IMPSAtomSort("uu"))
        val e_var = (freshVar("e",List(sort,y_var._1) ::: addCs), IMPSAtomSort("uu"))

        val inner   : IMPSMathExp = IMPSLambda(List(y_var),IMPSIndividual())
        val resolve : IMPSMathExp = IMPSApply(IMPSLambda(List(e_var),inner), List(sort))

        resolve
      }

      case IMPSQCIn(e1_u,e2_u) =>
      {
        // "lambda(x:uu,a:sets[uu], #(a(x)))"
        val e1    : IMPSMathExp = removeQCs(e1_u,addCs)
        val e2    : IMPSMathExp = removeQCs(e2_u,addCs)

        val x_var = (freshVar("x",List(e1,e2)          ::: addCs), IMPSAtomSort("uu"))
        val a_var = (freshVar("a",List(e1,e2,x_var._1) ::: addCs), IMPSSetSort(IMPSAtomSort("uu")))

        val target  : IMPSMathExp = IMPSIsDefined(IMPSApply(a_var._1,List(x_var._1)))
        val resolve : IMPSMathExp = IMPSApply(IMPSLambda(List(x_var,a_var),target),List(e1,e2))

        resolve
      }

      case IMPSQCSubsetEQ(e1_u, e2_u) =>
      {
        // "lambda(a,b:sets[uu], forall(x:uu, (x in a) implies (x in b)))"
        val e1    : IMPSMathExp = removeQCs(e1_u,addCs)
        val e2    : IMPSMathExp = removeQCs(e2_u,addCs)

        val a_var = (freshVar("a",List(e1,e2) ::: addCs), IMPSSetSort(IMPSAtomSort("uu")))
        val b_var = (freshVar("b",List(e1,e2) ::: addCs), IMPSSetSort(IMPSAtomSort("uu")))
        val x_var = (freshVar("x",List(e1,e2) ::: addCs), IMPSAtomSort("uu"))

        val addConstraints : List[IMPSMathExp]
            = List(e1,e2,a_var._1,b_var._1,x_var._1) ::: addCs

        val in1   : IMPSMathExp = removeQCs(IMPSQCIn(x_var._1,a_var._1), addConstraints)
        val in2   : IMPSMathExp = removeQCs(IMPSQCIn(x_var._1,b_var._1), addConstraints)

        val forall = IMPSForAll(List(x_var), IMPSImplication(in1,in2))
        val lambda = IMPSLambda(List(a_var,b_var),forall)

        IMPSApply(lambda,List(e1,e2))
      }

      case IMPSQCSubset(e1_u, e2_u) =>
      {
        // Not used?
        // "lambda(a,b:sets[uu], (a subseteq b) and not(a = b))"
        val e1    : IMPSMathExp = removeQCs(e1_u,addCs)
        val e2    : IMPSMathExp = removeQCs(e2_u,addCs)

        val a_var = (freshVar("a",List(e1,e2) ::: addCs), IMPSSetSort(IMPSAtomSort("uu")))
        val b_var = (freshVar("b",List(e1,e2) ::: addCs), IMPSSetSort(IMPSAtomSort("uu")))

        val addConstraints : List[IMPSMathExp]
        = List(e1,e2,a_var._1,b_var._1) ::: addCs

        val subs : IMPSMathExp = removeQCs(IMPSQCSubsetEQ(a_var._1,b_var._1), addConstraints)
        val eq   : IMPSMathExp = removeQCs(IMPSEquals(a_var._1,b_var._1)    , addConstraints)
        val neg  : IMPSMathExp = removeQCs(IMPSNegation(eq)                 , addConstraints)

        val lambda = IMPSLambda(List(a_var,b_var), IMPSConjunction(List(subs,neg)))
        IMPSApply(lambda,List(e1,e2))
      }
    }
  }
}

class IMPSDependencyException(message: String) extends Exception(message) {

  def this(message: String, cause: Throwable) {
    this(message)
    initCause(cause)
  }

  def this(cause: Throwable) {
    this(Option(cause).map(_.toString).orNull, cause)
  }

  def this() {
    this(null: String)
  }
}

