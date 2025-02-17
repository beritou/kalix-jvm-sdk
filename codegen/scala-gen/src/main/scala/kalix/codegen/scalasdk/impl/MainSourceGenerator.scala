/*
 * Copyright 2024 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kalix.codegen.scalasdk.impl

import kalix.codegen.File
import kalix.codegen.ModelBuilder
import kalix.codegen.ModelBuilder.Entity
import kalix.codegen.ModelBuilder.Service
import kalix.codegen._

/**
 * Responsible for generating Main and KalixFactory Java source from an entity model
 */
object MainSourceGenerator {

  import kalix.codegen.SourceGeneratorUtils._
  import ScalaGeneratorUtils._

  def generateUnmanaged(model: ModelBuilder.Model, mainPackageName: PackageNaming): Iterable[File] =
    Seq(mainSource(model, mainPackageName))

  def generateManaged(model: ModelBuilder.Model, mainPackageName: PackageNaming): Iterable[File] =
    Seq(kalixFactorySource(model, mainPackageName))

  def mainClassName(model: ModelBuilder.Model, mainPackageName: PackageNaming): ProtoMessageType =
    ProtoMessageType.noDescriptor("Main", mainPackageName)

  private[codegen] def mainSource(model: ModelBuilder.Model, mainPackageName: PackageNaming): File = {
    val mainClass = mainClassName(model, mainPackageName)

    val entityImports = model.entities.values.collect {
      case entity: ModelBuilder.EventSourcedEntity => entity.messageType.fullyQualifiedName
      case entity: ModelBuilder.ValueEntity        => entity.messageType.fullyQualifiedName
      case entity: ModelBuilder.ReplicatedEntity   => entity.messageType.fullyQualifiedName
    }.toSeq

    val serviceImports = model.services.values.collect {
      case service: ModelBuilder.ActionService => service.classNameQualified
      case view: ModelBuilder.ViewService      => view.classNameQualified
    }.toSeq

    val allImports = entityImports ++ serviceImports ++
      List("kalix.scalasdk.Kalix", "org.slf4j.LoggerFactory")

    implicit val imports: Imports =
      generateImports(Iterable.empty, mainClass.parent.scalaPackage, allImports)

    val entityRegistrationParameters = model.entities.values.toList
      .sortBy(_.messageType.name)
      .collect {
        case entity: ModelBuilder.EventSourcedEntity => s"new ${typeName(entity.messageType)}(_)"
        case entity: ModelBuilder.ValueEntity        => s"new ${typeName(entity.messageType)}(_)"
        case entity: ModelBuilder.ReplicatedEntity   => s"new ${typeName(entity.messageType)}(_)"
      }

    val serviceRegistrationParameters = model.services.values.toList
      .sortBy(_.messageType.name)
      .collect {
        case service: ModelBuilder.ActionService => s"new ${service.className}(_)"
        case view: ModelBuilder.ViewService      => s"new ${view.className}(_)"
      }

    val registrationParameters = entityRegistrationParameters ::: serviceRegistrationParameters

    File.scala(
      mainClass.parent.scalaPackage,
      mainClass.name,
      s"""|package ${mainClass.parent.scalaPackage}
        |
        |${writeImports(imports)}
        |
        |$unmanagedComment
        |
        |object ${mainClass.name} {
        |
        |  private val log = LoggerFactory.getLogger("${mainClass.parent.scalaPackage}.${mainClass.name}")
        |
        |  def createKalix(): Kalix = {
        |    // The KalixFactory automatically registers any generated Actions, Views or Entities,
        |    // and is kept up-to-date with any changes in your protobuf definitions.
        |    // If you prefer, you may remove this and manually register these components in a
        |    // `Kalix()` instance.
        |    KalixFactory.withComponents(
        |      ${registrationParameters.mkString(",\n      ")})
        |  }
        |
        |  def main(args: Array[String]): Unit = {
        |    log.info("starting the Kalix service")
        |    createKalix().start()
        |  }
        |}
        |""".stripMargin)
  }

  private[codegen] def kalixFactorySource(model: ModelBuilder.Model, mainPackageName: PackageNaming): File = {

    val entityImports = model.entities.values.flatMap { ety =>
      val imp =
        ety.messageType :: Nil
      ety match {
        case _: ModelBuilder.EventSourcedEntity =>
          ety.provider :: imp
        case _: ModelBuilder.ValueEntity =>
          ety.provider :: imp
        case _: ModelBuilder.ReplicatedEntity =>
          ety.provider :: imp
        case _ => imp
      }
    }

    val serviceImports = model.services.values.flatMap { serv =>
      serv match {
        case actionServ: ModelBuilder.ActionService =>
          List(actionServ.classNameQualified, actionServ.providerNameQualified)
        case view: ModelBuilder.ViewService =>
          List(view.classNameQualified, view.providerNameQualified)
        case _ => Nil
      }
    }

    val entityContextImports = model.entities.values.collect {
      case _: ModelBuilder.EventSourcedEntity =>
        List("kalix.scalasdk.eventsourcedentity.EventSourcedEntityContext")
      case _: ModelBuilder.ValueEntity =>
        List("kalix.scalasdk.valueentity.ValueEntityContext")
      case _: ModelBuilder.ReplicatedEntity =>
        List("kalix.scalasdk.replicatedentity.ReplicatedEntityContext")
    }.flatten

    val serviceContextImports = model.services.values.collect {
      case _: ModelBuilder.ActionService =>
        List("kalix.scalasdk.action.ActionCreationContext")
      case _: ModelBuilder.ViewService =>
        List("kalix.scalasdk.view.ViewCreationContext")
    }.flatten

    implicit val imports: Imports =
      generateImports(
        entityImports,
        mainPackageName.javaPackage,
        Seq("kalix.scalasdk.Kalix") ++ serviceImports ++ entityContextImports ++ serviceContextImports)

    def creator(messageType: ProtoMessageType): String = {
      if (imports.clashingNames.contains(messageType.name)) s"create${dotsToCamelCase(typeName(messageType))}"
      else s"create${messageType.name}"
    }

    val registrations = model.services.values
      .flatMap {
        case service: ModelBuilder.EntityService =>
          model.entities.get(service.componentFullName).toSeq.map {
            case entity: ModelBuilder.EventSourcedEntity =>
              s".register(${typeName(entity.provider)}(${creator(entity.messageType)}))"
            case entity: ModelBuilder.ValueEntity =>
              s".register(${typeName(entity.provider)}(${creator(entity.messageType)}))"
            case entity: ModelBuilder.ReplicatedEntity =>
              s".register(${typeName(entity.provider)}(${creator(entity.messageType)}))"
          }

        case service: ModelBuilder.ViewService =>
          List(s".register(${service.providerName}(${creator(service.impl)}))")

        case service: ModelBuilder.ActionService =>
          List(s".register(${service.providerName}(${creator(service.impl)}))")

      }
      .toList
      .sorted

    val entityCreators =
      model.entities.values.toList
        .sortBy(_.messageType.name)
        .collect {
          case entity: ModelBuilder.EventSourcedEntity =>
            s"${creator(entity.messageType)}: EventSourcedEntityContext => ${typeName(entity.messageType)}"
          case entity: ModelBuilder.ValueEntity =>
            s"${creator(entity.messageType)}: ValueEntityContext => ${typeName(entity.messageType)}"
          case entity: ModelBuilder.ReplicatedEntity =>
            s"${creator(entity.messageType)}: ReplicatedEntityContext => ${typeName(entity.messageType)}"
        }

    val serviceCreators = model.services.values.toList
      .sortBy(_.messageType.name)
      .collect {
        case service: ModelBuilder.ActionService =>
          s"${creator(service.impl)}: ActionCreationContext => ${typeName(service.impl)}"
        case view: ModelBuilder.ViewService =>
          s"${creator(view.impl)}: ViewCreationContext => ${typeName(view.impl)}"
      }

    val creatorParameters = entityCreators ::: serviceCreators

    File.scala(
      mainPackageName.javaPackage,
      "KalixFactory",
      s"""|package ${mainPackageName.javaPackage}
        |
        |${writeImports(imports)}
        |
        |$managedComment
        |
        |object KalixFactory {
        |
        |  def withComponents(
        |      ${creatorParameters.mkString(",\n      ")}): Kalix = {
        |    val kalix = Kalix()
        |    kalix
        |      ${Format.indent(registrations, 6)}
        |  }
        |}
        |""".stripMargin)
  }

}
