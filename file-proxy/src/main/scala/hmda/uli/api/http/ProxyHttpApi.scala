package hmda.proxy.api.http

import akka.NotUsed
import akka.http.scaladsl.model.{StatusCodes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.RouteDirectives.complete
import akka.http.scaladsl.server.{Directive, Directive0}
import akka.http.scaladsl.server.Route
import akka.util.ByteString
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import org.slf4j.Logger
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{Sink, Source}
import scala.concurrent.Future
import akka.stream.alpakka.s3._
import com.typesafe.config.ConfigFactory
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.AwsRegionProvider
import software.amazon.awssdk.regions.providers._
import akka.stream.alpakka.s3.ApiVersion.ListBucketVersion2
import akka.actor.ActorSystem
import scala.concurrent._
import scala.concurrent.Future
import akka.stream.scaladsl.{Sink, Source}
import akka.http.scaladsl.model.ContentTypes
import scala.util.{Failure, Success}
import akka.http.scaladsl.model.StatusCodes.BadRequest
import hmda.auth.OAuth2Authorization
import akka.util.Timeout
import scala.concurrent.duration._

object ProxyHttpApi {
  def create(log: Logger)(implicit ec: ExecutionContext, system: ActorSystem): OAuth2Authorization => Route = new ProxyHttpApi(log).proxyHttpRoutes _
}
private class ProxyHttpApi(log: Logger)(implicit ec: ExecutionContext, system: ActorSystem) {

  val config                  = ConfigFactory.load()
  val accessKeyId             = config.getString("aws.access-key-id")
  val secretAccess            = config.getString("aws.secret-access-key ")
  val region                  = config.getString("aws.region")
  val bucket                  = config.getString("aws.public-bucket")
  val environment             = config.getString("aws.environment")

  val dynamicYears        = config.getString("hmda.publication.years.dynamic").split(",").toList
  val irsYears            = config.getString("hmda.publication.years.irs").split(",").toList
  val snapshotYears       = config.getString("hmda.publication.years.snapshot").split(",").toList

  val awsCredentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccess))
  val awsRegionProvider: AwsRegionProvider = new AwsRegionProvider {
    override def getRegion: Region = Region.of(region)
  }

  val s3Settings = S3Settings(system)
    .withBufferType(MemoryBufferType)
    .withCredentialsProvider(awsCredentialsProvider)
    .withS3RegionProvider(awsRegionProvider)
    .withListBucketApiVersion(ListBucketVersion2)

  def proxyHttpRoutes(oAuth2Authorization: OAuth2Authorization): Route = {
    encodeResponse {
      pathPrefix("file") {
        //Modified Lar Route CSV
        path("modifiedLar"/ "year" / Segment / "institution" / Segment / "csv") { (year, lei) =>
          (extractUri & get) { uri =>
            checkYearAvailable(dynamicYears, year) {
              val s3Key = "prod/modified-lar/" + year + "/" + lei + ".csv"
              streamingS3Route(s3Key)
            }
          }
        } ~
        //Modified Lar Route TXT
        path("modifiedLar"/ "year" / Segment / "institution" / Segment / "txt") { (year, lei) =>
          (extractUri & get) { uri =>
            checkYearAvailable(dynamicYears, year) {
              val s3Key = "prod/modified-lar/" + year + "/" + lei + ".txt"
              streamingS3Route(s3Key)
            }
          }
        } ~
        //IRS Report Route
        path("reports" / "irs" / "year" / Segment / "institution" / Segment) { (year, lei) =>
          (extractUri & get) { uri =>
            oAuth2Authorization.authorizeTokenWithLei(lei) { _ =>
              checkYearAvailable(irsYears, year) {
                val s3Key = "prod/reports/disclosure/" + year + "/" + lei + "/nationwide/IRS.csv"
                streamingS3Route(s3Key)
              }
            }
          }
        }
      }
    } 
  }

  private def retrieveData(path: String): Future[Option[Source[ByteString, NotUsed]]] = {
    val timeout: Timeout = Timeout(config.getInt("hmda.http.timeout").seconds)
    S3.download("cfpb-hmda-public", path).withAttributes(S3Attributes.settings(s3Settings)).runWith(Sink.head)
      .map(opt => opt.map { case (source, _) => source })
  }

  private def streamingS3Route(s3Key: String): Route = {
    val fStream: Future[Source[ByteString, NotUsed]] = retrieveData(s3Key).flatMap {
      case Some(stream) =>
        Future(stream)
      case None =>
        Future(Source.empty)
    }

    onComplete(fStream){
      case Success(stream) => complete(HttpEntity(ContentTypes.`text/csv(UTF-8)`, stream))
      case Failure(error) => complete(StatusCodes.BadRequest)
    }
  }

  private def checkYearAvailable(availableYears: List[String], year: String): Directive0 = {
    Directive[Unit](route =>
      if (availableYears.contains(year)) route(())
      else complete((BadRequest, year + " is not available for dataset"))
    )

  }

}
