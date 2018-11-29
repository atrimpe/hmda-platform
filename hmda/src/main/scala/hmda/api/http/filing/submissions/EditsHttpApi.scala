package hmda.api.http.filing.submissions

import akka.actor.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.event.LoggingAdapter
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{encodeResponse, handleRejections}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.{
  cors,
  corsRejectionHandler
}
import hmda.api.http.directives.HmdaTimeDirectives
import hmda.messages.submission.SubmissionProcessingCommands.GetHmdaValidationErrorState
import hmda.model.filing.submission.{SubmissionId, SubmissionStatus}
import hmda.model.processing.state.{EditSummary, HmdaValidationErrorState}
import hmda.persistence.submission.{EditDetailsPersistence, HmdaValidationError}
import hmda.util.http.FilingResponseUtils._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import hmda.api.http.model.filing.submissions._
import hmda.api.http.codec.filing.submission.SubmissionStatusCodec._
import hmda.api.http.codec.filing.submission.EditDetailsSummaryCodec._
import hmda.auth.OAuth2Authorization
import hmda.messages.submission.EditDetailsCommands.GetEditRowCount
import hmda.messages.submission.EditDetailsEvents.{
  EditDetailsAdded,
  EditDetailsPersistenceEvent,
  EditDetailsRowCounted
}
import io.circe.generic.auto._
import hmda.model.filing.EditDescriptionLookup._
import hmda.query.HmdaQuery._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex
import scala.util.{Failure, Success}

trait EditsHttpApi extends HmdaTimeDirectives {

  implicit val system: ActorSystem
  implicit val materializer: ActorMaterializer
  val log: LoggingAdapter
  implicit val ec: ExecutionContext
  implicit val timeout: Timeout
  val sharding: ClusterSharding

  //institutions/<institutionId>/filings/<period>/submissions/<submissionId>/edits
  def editsSummaryPath(oAuth2Authorization: OAuth2Authorization): Route =
    path(
      "institutions" / Segment / "filings" / Segment / "submissions" / IntNumber / "edits") {
      (lei, period, seqNr) =>
        oAuth2Authorization.authorizeTokenWithLei(lei) { _ =>
          timedGet { uri =>
            val submissionId = SubmissionId(lei, period, seqNr)
            val hmdaValidationError = sharding
              .entityRefFor(HmdaValidationError.typeKey,
                            s"${HmdaValidationError.name}-$submissionId")

            val fEdits
              : Future[HmdaValidationErrorState] = hmdaValidationError ? (ref =>
              GetHmdaValidationErrorState(submissionId, ref))

            onComplete(fEdits) {
              case Success(edits) =>
                val syntactical = SyntacticalEditSummaryResponse(
                  edits.syntactical.map(toEditSummaryResponse).toSeq)
                val validity = ValidityEditSummaryResponse(
                  edits.validity.map(toEditSummaryResponse).toSeq)
                val quality = QualityEditSummaryResponse(
                  edits.quality.map(toEditSummaryResponse).toSeq,
                  edits.qualityVerified)
                val `macro` = MacroEditSummaryResponse(
                  edits.`macro`.map(toEditSummaryResponse).toSeq,
                  edits.macroVerified)
                val editsSummaryResponse =
                  EditsSummaryResponse(
                    syntactical,
                    validity,
                    quality,
                    `macro`,
                    SubmissionStatus.valueOf(edits.statusCode))
                complete(ToResponseMarshallable(editsSummaryResponse))
              case Failure(e) =>
                failedResponse(StatusCodes.InternalServerError, uri, e)
            }
          }
        }
    }

  //institutions/<institutionId>/filings/<period>/submissions/<submissionId>/edits/edit
  def editDetailsPath(oAuth2Authorization: OAuth2Authorization): Route = {
    val editNameRegex: Regex = new Regex("""[SVQ]\d\d\d(?:-\d)?""")
    path(
      "institutions" / Segment / "filings" / Segment / "submissions" / IntNumber / "edits" / editNameRegex) {
      (lei, period, seqNr, editName) =>
        oAuth2Authorization.authorizeTokenWithLei(lei) { _ =>
          timedGet { uri =>
            parameters('page.as[Int] ? 1) { page =>
              val submissionId = SubmissionId(lei, period, seqNr)
              val persistenceId =
                s"${EditDetailsPersistence.name}-$submissionId"
              val editDetailsPersistence = sharding
                .entityRefFor(EditDetailsPersistence.typeKey,
                              s"${EditDetailsPersistence.name}-$submissionId")

              val fEditRowCount
                : Future[EditDetailsRowCounted] = editDetailsPersistence ? (
                  ref => GetEditRowCount(editName, ref))

              val fDetails = for {
                editRowCount <- fEditRowCount
                s = EditDetailsSummary(editName,
                                       Nil,
                                       uri.path.toString(),
                                       page,
                                       editRowCount.count)
                summary <- editDetails(persistenceId, s)
              } yield summary

              onComplete(fDetails) {
                case Success(summary) =>
                  complete(ToResponseMarshallable(summary))
                case Failure(e) =>
                  failedResponse(StatusCodes.InternalServerError, uri, e)
              }
            }
          }
        }

    }
  }

  def editsRoutes(oAuth2Authorization: OAuth2Authorization): Route = {
    handleRejections(corsRejectionHandler) {
      cors() {
        encodeResponse {
          editsSummaryPath(oAuth2Authorization) ~ editDetailsPath(
            oAuth2Authorization)
        }
      }
    }
  }

  private def toEditSummaryResponse(e: EditSummary): EditSummaryResponse = {
    EditSummaryResponse(e.editName, lookupDescription(e.editName))
  }

  private def editDetails(
      persistenceId: String,
      summary: EditDetailsSummary): Future[EditDetailsSummary] = {
    val editDetails = eventEnvelopeByPersistenceId(persistenceId)
      .map(envelope => envelope.event.asInstanceOf[EditDetailsPersistenceEvent])
      .collect {
        case EditDetailsAdded(editDetail) => editDetail
      }
      .filter(e => e.edit == summary.editName)
      .drop(summary.fromIndex)
      .take(summary.count)
      .runWith(Sink.seq)
    editDetails.map(e => summary.copy(rows = e.flatMap(r => r.rows)))
  }

}