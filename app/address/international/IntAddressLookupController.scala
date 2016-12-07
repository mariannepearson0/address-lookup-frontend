package address.international

import address.uk._
import address.uk.service.AddressLookupService
import com.fasterxml.uuid.{EthernetAddress, Generators}
import config.FrontendGlobal
import keystore.MemoService
import play.api.Play.current
import play.api.i18n.Messages.Implicits._
import play.api.mvc.{Action, AnyContent, Request, Result}
import uk.gov.hmrc.address.v2.International
import uk.gov.hmrc.play.frontend.controller.FrontendController
import views.html.addressint._

import scala.concurrent.{ExecutionContext, Future}

object IntAddressLookupController extends IntAddressLookupController(
  Services.configuredAddressLookupService,
  Services.metricatedKeystoreService,
  FrontendGlobal.executionContext)


class IntAddressLookupController(lookup: AddressLookupService, memo: MemoService, val ec: ExecutionContext) extends FrontendController {

  private implicit val xec = ec

  import IntAddressForm.addressForm
  import address.ViewConfig._

  private val uuidGenerator = Generators.timeBasedGenerator(EthernetAddress.fromInterface())

  //-----------------------------------------------------------------------------------------------

  def getEmptyForm(tag: String, guid: Option[String], continue: Option[String]): Action[AnyContent] =
    TaggedAction.withTag(tag).apply {
      implicit request =>
        if (cfg(tag).allowInternationalAddress) {
          Ok(basicBlankForm(tag, guid, continue))
        } else {
          BadRequest("International addresses are not available")
        }
    }

  private def basicBlankForm(tag: String, guid: Option[String], continue: Option[String])(implicit request: Request[_]) = {
    val actualGuid = guid.getOrElse(uuidGenerator.generate.toString)
    val cu = continue.getOrElse(defaultContinueUrl)
    val ad = IntAddressData(guid = actualGuid, continue = cu)
    val bound = addressForm.fill(ad)
    blankIntForm(tag, cfg(tag), bound, noMatchesWereFound = false, exceededLimit = false)
  }

  //-----------------------------------------------------------------------------------------------

  def postSelected(tag: String): Action[AnyContent] =
    TaggedAction.withTag(tag).async {
      implicit request =>
        //println("form2: " + PrettyMapper.writeValueAsString(request.body))
        val bound = addressForm.bindFromRequest()(request)
        if (bound.errors.nonEmpty) {
          Future.successful(BadRequest(blankIntForm(tag, cfg(tag), bound, noMatchesWereFound = false, exceededLimit = false)))

        } else {
          continueToCompletion(tag, bound.get, request)
        }
    }

  private def continueToCompletion(tag: String, addressData: IntAddressData, request: Request[_]): Future[Result] = {
    val response = addressData.asInternational
    memo.storeSingleIntResponse(tag, addressData.guid, response) map {
      httpResponse =>
        SeeOther(addressData.continue + "?id=" + addressData.guid)
    }
  }

  //-----------------------------------------------------------------------------------------------

  def confirmation(tag: String, id: String): Action[AnyContent] =
    TaggedAction.withTag(tag).async {
      implicit request =>
        require(id.nonEmpty)
        val fuResponse = memo.fetchSingleIntResponse(tag, id)
        fuResponse.map {
          response: Option[International] =>
            if (response.isEmpty) {
              val emptyFormRoute = routes.IntAddressLookupController.getEmptyForm(tag, None, None)
              TemporaryRedirect(emptyFormRoute.url)
            } else {
              Ok(userSuppliedInternationalPage(tag, cfg(tag), response.get))
            }
        }
    }
}