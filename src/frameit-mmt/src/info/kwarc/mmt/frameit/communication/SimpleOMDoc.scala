package info.kwarc.mmt.frameit.communication

import info.kwarc.mmt.api.notations.NotationContainer
import info.kwarc.mmt.api.objects.{OMMOD, OMS, Term}
import info.kwarc.mmt.api.symbols.{Declaration, FinalConstant, TermContainer, Visibility}
import info.kwarc.mmt.api.{NamespaceMap, Path}
import info.kwarc.mmt.frameit.archives.Foundation.{IntegerLiterals, RealLiterals, StringLiterals}
import info.kwarc.mmt.lf.ApplySpine
import io.circe.Decoder
import io.circe.generic.extras.ConfiguredJsonCodec

object SimpleOMDoc {
  // IMPORTANT: keep the following lines. Do not change unless you know what you're doing
  //
  //            they control how the JSON en- and decoders treat subclasses of [[SimpleOMDoc.STerm]]
  import io.circe.Json
  import io.circe.syntax._
  import io.circe.generic.extras.semiauto._
  import io.circe.generic.extras.auto._
  import io.circe.generic.extras.Configuration
  // IMPORTANT: end

  /**
    * The type to represent MMT URIs
    * Currently just strings, but retain flexibility to change it in the future
    */
  type SURI = String

  object JsonConfig {
    implicit val jsonConfig: Configuration = Configuration.default
      .withDiscriminator("kind")
      .copy(transformConstructorNames = oldCtorName => {
        // Cannot declare this in the outer object due to some weird
        // errors with circe-generic-extras macro magic
        val rewriteMap = Map(
          SOMA.getClass -> "OMA",
          SOMS.getClass -> "OMS",
          SFloatingPoint.getClass -> "OMF",
          SString.getClass -> "OMSTR",
          SInteger.getClass -> "OMI"
        ).map { case (key, value) => (key.getSimpleName.replace("$", ""), value) }

        rewriteMap.getOrElse(oldCtorName, oldCtorName)
      })
  }

  // vvv DO NOT REMOVE (even if IntelliJ marks it as unused)
  // vvv
  import JsonConfig.jsonConfig

  // implicit val stermDecoder: Decoder[STerm] = deriveConfiguredDecoder[STerm]

  @ConfiguredJsonCodec
  sealed trait STerm

  @ConfiguredJsonCodec
  case class SOMS(uri: SURI) extends STerm

  @ConfiguredJsonCodec
  case class SOMA(applicant: STerm, arguments: List[STerm]) extends STerm

  @ConfiguredJsonCodec
  case class SInteger(value: Int) extends STerm

  @ConfiguredJsonCodec
  case class SFloatingPoint(float: Double) extends STerm

  @ConfiguredJsonCodec
  case class SString(string: String) extends STerm

  @ConfiguredJsonCodec
  case class SDeclaration(uri: SURI, tp: STerm, df: Option[STerm])

  final case class ConversionException(private val message: String = "",
                                   private val cause: Throwable = None.orNull)
    extends Exception(message, cause)


  object OMDocBridge {
    def encode(decl: Declaration): SDeclaration = decl match {
      case f: FinalConstant => f.tp match {
        case Some(tp) => SDeclaration(f.path.toString, encode(tp), f.df.map(encode))
        case _ => throw ConversionException("cannot convert Declaration not containing type to SimpleOMDoc")
      }
      case _ => throw ConversionException(s"cannot convert declarations other than FinalConstant to SimpleOMDoc; declaration was ${decl}")
    }

    def decode(sdecl: SDeclaration): Declaration = {
      val path = Path.parseS(sdecl.uri, NamespaceMap.empty)

      new FinalConstant(
        OMMOD(path.module),
        path.name,
        alias = Nil,
        tpC = TermContainer.asParsed(decode(sdecl.tp)),
        dfC = TermContainer.asParsed(sdecl.df.map(decode)),
        rl = None,
        notC = new NotationContainer,
        vs = Visibility.public
      )
    }

    def encode(tm: Term): STerm = tm match {
      case OMS(path) => SOMS(path.toString)
      // Only support OMA applications in LF style
      case ApplySpine(fun, args) => SOMA(encode(fun), args.map(encode))

      case IntegerLiterals(value) => SInteger(value.intValue()) // TODO: overflow possible
      case RealLiterals(value) => SFloatingPoint(value)
      case StringLiterals(value) => SString(value)

      case _ => throw ConversionException(s"encountered term for which there is no SimpleOMDoc analogon: ${tm}")
    }

    def decode(stm: STerm): Term = stm match {
      case SOMS(uri) => OMS(Path.parseS(uri, NamespaceMap.empty))
      case SOMA(fun, arguments) => ApplySpine(decode(fun), arguments.map(decode): _*)
      case SInteger(value) => IntegerLiterals(value)
      case SFloatingPoint(value) => RealLiterals(value)
      case SString(value) => StringLiterals(value)
    }
  }

  object JSONBridge {
    def encodeDeclaration(decl: SDeclaration): Json = decl.asJson

    def encode(stm: STerm): Json = stm.asJson
    def decodeTerm(str: String): STerm = io.circe.parser.decode[STerm](str).getOrElse(
      throw ConversionException(s"could not decode string to STerm: ${str}")
    )
  }
}