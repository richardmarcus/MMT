package info.kwarc.mmt.odk.OpenMath.Coding
import info.kwarc.mmt.api._
import info.kwarc.mmt.odk.OpenMath._
import info.kwarc.mmt.api.objects._
import info.kwarc.mmt.api.uom.OMLiteral._
import info.kwarc.mmt.api.utils.URI

/**
  * Decode / Encode MMT Terms as OpenMath objects.
  */
class OMMMTCoding(default: => URI) extends OMCoding[Term] {

  /** encode an arbitrary OpenMath term as an MMT Term */
  def encode(om : OMAny) : Term = {
    val absolute = om.absolutize(default)
    actencode(absolute)(absolute)
  }

  /**
    * acts on an absolutized OpenMath term
    * @param om
    * @return
    */
  protected def actencode(om : OMAny)(implicit toplevel: OMAny) : Term = om match {
    // Object is just a top-level wrapper
    case OMObject(expr,_,_,_) => actencode(expr)

    // reference: Resolve it
    case OMReference(href, id) =>
      actencode(toplevel.getAnyById(href.toString).getOrElse(throw GeneralError(s"invalid reference to ${href.toString}")))

    // primitives: direct translation
    case OMInteger(i,id) => OMI(i)
    case OMFloat(r,id) => OMF(r)
    case OMString(s,id) => OMSTR(s)
    case OMBytes(list,id) => ??? // TODO: What to do with bytes?

    // symbol
    case OMSymbol(name,cd,id,cdbase) => OMS(DPath(cdbase.get) ? cd ? name)

    // variables (in all forms)
    case OMVariable(name,id) => OMV(name)
    case OMVarVar(omvar) => OMV(omvar.name)
    case v @ OMAttVar(OMAttributionPairs(pairslist,inner_id,inner_cdbase),va,id) => OMV(v.name)

    // application
    case OMApplication(f,args,id,cdbase) => OMA(actencode(f),args map actencode)

    // binding
    case OMBinding(binder,OMBindVariables(vars,inner_id),expr,id,cdbase) =>
      OMBIND(actencode(binder), vars map (v => VarDecl(LocalName(v.name))), actencode(expr))
    case OMBindVariables(vars,id) => throw GeneralError(s"bound variables only supported within an OMBinding")

    // attribution: not supported
    case OMAttribution(OMAttributionPairs(pairslist,inner_id,inner_cdbase),expr,id,cdbase) => ???
    case OMAttributionPairs(pairs,id,cdbase) => ???

    // errors and foreign
    case OMError(sym,params,id,cdbase) => OMSTR("Error: " + sym.cd + "?" + sym.name) // TODO
    case OMForeign(any,encoding,id,cdbase) => ??? // TODO: Do we want to have this as an unknown OMLIT?
  }

  protected def relativize(om : OMExpression) : OMExpression = {
    om.mapComponents {
      case OMSymbol(name, cd, id, cdbase) => {
        cd.startsWith(default.toString + "?") match {
          case true => OMSymbol(name, cd.substring(default.toString.length + 1), id, cdbase)
          case false => OMSymbol(name, cd, id, cdbase)
        }
      }
      case ob => ob
    }
  }

  def decodeAnyVal(t : Term) : OMAnyVal = t match {
    case OMI(i) => OMInteger(i,None)
    case OMF(r) => OMFloat(r,None)
    case OMSTR(s) => OMString(s,None)
    case OMS(p) => OMSymbol(p.name.toString,p.module.toString,None,None)
    case OMV(name) => OMVariable(name.toString,None)
    case OMA(f,args) => OMApplication(decexpr(f),args map decexpr,None,None)
  }
  private def decexpr(t : Term) = decode(t) match {
    case expr: OMExpression => relativize(expr)
    case _ => throw new Exception("Does not yield OMExpression:" + t)
  }
  def decode(t : Term) : OMAny = decodeAnyVal(t) // should probably recurse into the above
}

object GAPEncoding extends OMMMTCoding(URI("http://www.gap-system.org"))
