package com.sageserpent.plutonium

import java.lang.reflect.Method

import net.sf.cglib.proxy.MethodProxy
import net.sf.cglib.core.Signature

import scalaz.{-\/, \/, \/-}


/**
  * Created by Gerard on 23/01/2016.
  */

object Patch {
  type WrappedArgument = \/[AnyRef, Recorder#ItemReconstitutionData[_ <: Identified]]

  def wrap(argument: AnyRef): WrappedArgument = argument match {
    case argumentRecorder: Recorder => \/-(argumentRecorder.itemReconstitutionData)
    case _ => -\/(argument)
  }

  def apply(targetRecorder: Recorder, method: Method, arguments: Array[AnyRef], methodProxy: MethodProxy) =
    new Patch(method,
      targetRecorder.itemReconstitutionData,
      arguments map wrap,
      methodProxy.getSignature)


  case class SerializableStandin(methodPieces: (Class[_ <: Identified], String, Array[Class[_]]),
                                 targetReconstitutionData: Recorder#ItemReconstitutionData[_ <: Identified],
                                 wrappedArguments: Array[Patch.WrappedArgument],
                                 signaturePieces: (String, String)) extends java.io.Serializable {
    def readResolve(): Any = {
      val (declaringClassOfMethod, methodName, methodParameterTypes) = methodPieces
      val (name, descriptor) = signaturePieces
      new Patch(declaringClassOfMethod.getMethod(methodName, methodParameterTypes: _*), targetReconstitutionData, wrappedArguments, new Signature(name, descriptor))
    }
  }
}

class Patch(method: Method,
            override val targetReconstitutionData: Recorder#ItemReconstitutionData[_ <: Identified],
            wrappedArguments: Array[Patch.WrappedArgument],
            signature: Signature) extends AbstractPatch(method) {
  import Patch._

  override val argumentReconstitutionDatums: Seq[Recorder#ItemReconstitutionData[_ <: Identified]] =
    wrappedArguments collect { case \/-(itemReconstitutionData) => itemReconstitutionData }

  def unwrap(identifiedItemAccess: IdentifiedItemAccess)(wrappedArgument: WrappedArgument) = wrappedArgument.fold(identity, identifiedItemAccess.reconstitute(_))

  def apply(identifiedItemAccess: IdentifiedItemAccess): Unit = {
    val targetBeingPatched = identifiedItemAccess.reconstitute(targetReconstitutionData)
    val proxyClassOfTarget = targetBeingPatched.getClass()
    val methodProxy = MethodProxy.find(proxyClassOfTarget, signature)
    methodProxy.invoke(targetBeingPatched, wrappedArguments map unwrap(identifiedItemAccess))
  }

  def checkInvariant(identifiedItemAccess: IdentifiedItemAccess): Unit = {
    identifiedItemAccess.reconstitute(targetReconstitutionData).checkInvariant()
  }

  private def writeReplace(): Any = {
    val methodPieces = (method.getDeclaringClass.asInstanceOf[Class[_ <: Identified]], method.getName, method.getParameterTypes)
    val signaturePieces = signature.getName -> signature.getDescriptor
    SerializableStandin(methodPieces, targetReconstitutionData, wrappedArguments, signaturePieces)
  }
}
