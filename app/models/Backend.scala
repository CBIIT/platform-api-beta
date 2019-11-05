package models

import clickhouse.ClickHouseProfile
import javax.inject.Inject
import models.Helpers._
import play.api.{Configuration, Environment, Logger}
import com.sksamuel.elastic4s._
import com.sksamuel.elastic4s.http.JavaClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.util.{Failure, Success}
import models.entities.Configuration._
import models.entities.Configuration.JSONImplicits._
import Entities._
import Entities.JSONImplicits._
import models.entities.Associations._
import models.entities._
import models.entities.HealthCheck.JSONImplicits._
import play.api.db.slick.DatabaseConfigProvider
import play.db.NamedDatabase

class Backend @Inject()(@NamedDatabase("default") protected val dbConfigProvider: DatabaseConfigProvider,
                        config: Configuration,
                        env: Environment) {
  val logger = Logger(this.getClass)

  val defaultOTSettings = loadConfigurationObject[OTSettings]("ot", config)
  val defaultESSettings = defaultOTSettings.elasticsearch

  /** return meta information loaded from ot.meta settings */
  lazy val getMeta: Meta = defaultOTSettings.meta

  def getStatus(isOk: Boolean): HealthCheck = isOk match {
    case true => HealthCheck(true, "All good!")
    case false => HealthCheck(false, "Hmm, something wrong is going on here!")
  }

  lazy val getESClient = ElasticClient(JavaClient(
    ElasticProperties(s"http://${defaultESSettings.host}:${defaultESSettings.port}")))

  lazy val dbRetriever = new DatabaseRetriever(dbConfigProvider.get[ClickHouseProfile], defaultOTSettings)

  lazy val esRetriever = new ElasticRetriever(getESClient)
  // we must import the dsl
  import com.sksamuel.elastic4s.ElasticDsl._

  def getTargets(ids: Seq[String]): Future[IndexedSeq[Target]] = {
    val targetIndexName = defaultESSettings.entities
      .find(_.name == "target").map(_.index).getOrElse("targets")
    esRetriever.getIds(targetIndexName, ids, Target.fromJsValue)
  }

  def getDrugs(ids: Seq[String]): Future[IndexedSeq[Drug]] = {
    val drugIndexName = defaultESSettings.entities
      .find(_.name == "drug").map(_.index).getOrElse("drugs")

    esRetriever.getIds(drugIndexName, ids, Drug.fromJsValue)
  }

  def altSearch(qString: String, pagination: Option[Pagination] = Option(Pagination.mkDefault),
                entities: Seq[ElasticsearchEntity] = defaultESSettings.entities): Future[AltSearchResults] =
    esRetriever.getAltSearchResultSet(entities, qString,pagination.get)

  def search(qString: String, pagination: Option[Pagination] = Option(Pagination.mkDefault),
             entities: Seq[ElasticsearchEntity] = defaultESSettings.entities): Future[SearchResults] =
    esRetriever.getSearchResultSet(entities, qString, pagination.get)

  def getAssociationsDiseaseFixed(id: String, expansionId: Option[String], pagination: Pagination): Future[Associations] = {
    val expandedByLUT: Option[LUTableSettings] =
      expansionId.flatMap(x => dbRetriever.diseaseNetworks.get(x))

    dbRetriever
      .computeAssociationsDiseaseFixed(id,
        expandedByLUT,
        defaultOTSettings.clickhouse.harmonic.datasources, pagination)
  }

  def getAssociationsTargetFixed(id: String, expansionId: Option[String], pagination: Pagination): Future[Associations] = {
    val expandedByLUT: Option[LUTableSettings] =
      expansionId.flatMap(x => dbRetriever.targetNetworks.get(x))

    dbRetriever
      .computeAssociationsTargetFixed(id,
        expandedByLUT,
        defaultOTSettings.clickhouse.harmonic.datasources, pagination)
  }
}
