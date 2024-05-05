package com.teknei.bid.controller.rest;

import static com.teknei.bid.service.validation.JsonValidation.validateJson;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.io.FileUtils;
import org.jnbis.api.Jnbis;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Base64Utils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import com.machinezoo.sourceafis.FingerprintMatcher;
import com.machinezoo.sourceafis.FingerprintTemplate;
import com.teknei.bid.controller.rest.util.crypto.Decrypt;
import com.teknei.bid.controller.rest.util.crypto.TokenUtils;
import com.teknei.bid.dto.BiometricCaptureRequestIdentDTO;
import com.teknei.bid.dto.BiometricCaptureRequestIdentToReceiveDTO;
import com.teknei.bid.dto.CompareFacialRequestDTO;
import com.teknei.bid.dto.DocumentPictureRequestDTO;
import com.teknei.bid.dto.IneDetailDTO;
import com.teknei.bid.dto.OperationResult;
import com.teknei.bid.service.remote.BiometricClient;
import com.teknei.bid.service.remote.FacialClient;
import com.teknei.bid.service.remote.IdentificationClient;
import com.teknei.bid.util.LogUtil;

import feign.FeignException;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

@RestController
@RequestMapping(value = "/rest/v3/enrollment/biometric")
@CrossOrigin
public class EnrollmentBiometricController {

	@Autowired
	private BiometricClient biometricClient;
	@Autowired
	private FacialClient facialClient;
	@Autowired
	private IdentificationClient identificationClient;
	@Autowired
	private Decrypt decrypt;
	@Autowired
	private TokenUtils tokenUtils;
	private static final String[] FIELDS_ALL_FINGERS = { "ll", "lr", "lm", "li", "lt", "rl", "rr", "rm", "ri", "rt" };
	private static final String[] FIELDS_SLAPS = { "ts", "rs", "ls" };
	private static final String[] FIELDS_FACE = { "facial" };
	private static final Logger log = LoggerFactory.getLogger(EnrollmentBiometricController.class);

	@ApiOperation(value = "Adds the binary information from the cyphered capture of the slaps. Expects a JSON like {'operationId' : 1, 'scanId' : 'currentScan' , 'documentId' : 'currentDocumentManagerId' , 'rs' : 'Right slap' , 'ls : 'left slap' , 'ts' : 'thumbs slap'")
	@RequestMapping(value = "/minuciasSlapsCyphered", method = RequestMethod.POST, produces = "application/json;charset=utf-8")
	public ResponseEntity<OperationResult> addMinuciasSlapsCyphered(@RequestBody String jsonRequest,
			HttpServletRequest request) {
		// log.info("lblancas: "+this.getClass().getName()+".{addMinuciasSlapsCyphered()
		// }");
		String username = (String) tokenUtils.getExtraInfo(request).get(TokenUtils.DETAILS_USERNAME_MAP_NAME);
		OperationResult operationResult = new OperationResult();
		JSONObject operationRequestJSON = null;
		if (!validateJson(jsonRequest)) {
			operationResult.setErrorMessage("Bad Request");
			return new ResponseEntity<>(operationResult, HttpStatus.BAD_REQUEST);
		}
		operationRequestJSON = new JSONObject(jsonRequest);
		String type = operationRequestJSON.optString("type", "SLAPS");
		Long operationId = operationRequestJSON.getLong("operationId");
		operationRequestJSON = updateJsonRequest(operationRequestJSON, FIELDS_SLAPS);
		operationRequestJSON.put("username", username);
		try {
			String jsonToSend = updateRequest(operationRequestJSON.toString());
			ResponseEntity<String> responseEntity = biometricClient.addMinucias(jsonToSend, operationId, 2);
			if (responseEntity == null) {
				throw new IllegalArgumentException();
			} else if (responseEntity.getStatusCode().is4xxClientError()) {
				operationResult.setResultOK(false);
				operationResult.setErrorMessage(responseEntity.getBody());
				return new ResponseEntity<>(operationResult, responseEntity.getStatusCode());
			} else if (responseEntity.getStatusCode().is5xxServerError()) {
				operationResult.setResultOK(false);
				operationResult.setErrorMessage(responseEntity.getBody());
				return new ResponseEntity<>(operationResult, HttpStatus.UNPROCESSABLE_ENTITY);
			}
			operationResult.setResultOK(true);
			operationResult.setErrorMessage("Se han almacenado correctamente los datos biometricos");
			return new ResponseEntity<>(operationResult, HttpStatus.OK);
		} catch (FeignException fe) {
			ResponseEntity<OperationResult> resultResponseEntity = parseFeign(fe);
			if (resultResponseEntity.getBody().getErrorMessage().equals("40003")) {
				ResponseEntity<String> responseEntity = null;
				try {
					responseEntity = biometricClient.searchBySlaps(operationRequestJSON.toString());
					String bodyString = responseEntity.getBody();
					JSONObject jsonObject = new JSONObject(bodyString);
					String id = jsonObject.getString("id");
					OperationResult operationResult1 = new OperationResult();
					operationResult1.setResultOK(false);
					JSONObject operationJsonResult = new JSONObject();
					operationJsonResult.put("id", Long.valueOf(id));
					operationJsonResult.put("status", "40003");
					operationResult1.setErrorMessage(operationJsonResult.toString());
					return new ResponseEntity<>(operationResult1, HttpStatus.UNPROCESSABLE_ENTITY);
				} catch (Exception e) {
					log.error("Duplicated record, unable to find related one with message: {}", e.getMessage());
					return resultResponseEntity;
				}
			} else if (resultResponseEntity.getBody().getErrorMessage().equals("40006")) {
				return new ResponseEntity<>(resultResponseEntity.getBody(), HttpStatus.PRECONDITION_FAILED);
			} else {
				return resultResponseEntity;
			}
		} catch (Exception e) {
			log.error("Error in addMinuciasSlaps for: {} - {} with message: {}", operationId, type, e.getMessage());
			operationResult.setResultOK(false);
			operationResult.setErrorMessage("40002");
			return new ResponseEntity<>(operationResult, HttpStatus.UNPROCESSABLE_ENTITY);
		}
	}

	@Deprecated
	@ApiOperation(value = "Adds the binary information from the capture of the slaps. Expects a JSON like {'operationId' : 1, 'scanId' : 'currentScan' , 'documentId' : 'currentDocumentManagerId' , 'rs' : 'Right slap' , 'ls : 'left slap' , 'ts' : 'thumbs slap'")
	@RequestMapping(value = "/minuciasSlaps", method = RequestMethod.POST, produces = "application/json;charset=utf-8")
	public ResponseEntity<OperationResult> addMinuciasSlaps(@RequestBody String jsonRequest,
			HttpServletRequest request) {
		// log.info("lblancas: "+this.getClass().getName()+".{addMinuciasSlaps() }");
		String username = (String) tokenUtils.getExtraInfo(request).get(TokenUtils.DETAILS_USERNAME_MAP_NAME);
		OperationResult operationResult = new OperationResult();
		JSONObject operationRequestJSON = null;
		if (!validateJson(jsonRequest)) {
			operationResult.setErrorMessage("Bad Request");
			return new ResponseEntity<>(operationResult, HttpStatus.BAD_REQUEST);
		}
		operationRequestJSON = new JSONObject(jsonRequest);
		String type = operationRequestJSON.optString("type", "SLAPS");
		Long operationId = operationRequestJSON.getLong("operationId");
		try {
			String jsonToSend = updateRequest(jsonRequest);
			JSONObject jsonObject = new JSONObject(jsonToSend);
			jsonObject.put("username", username);
			ResponseEntity<String> responseEntity = biometricClient.addMinucias(jsonObject.toString(), operationId, 2);
			if (responseEntity == null) {
				throw new IllegalArgumentException();
			} else if (responseEntity.getStatusCode().is4xxClientError()) {
				operationResult.setResultOK(false);
				operationResult.setErrorMessage(responseEntity.getBody());
				return new ResponseEntity<>(operationResult, responseEntity.getStatusCode());
			} else if (responseEntity.getStatusCode().is5xxServerError()) {
				operationResult.setResultOK(false);
				operationResult.setErrorMessage(responseEntity.getBody());
				return new ResponseEntity<>(operationResult, HttpStatus.UNPROCESSABLE_ENTITY);
			}
			operationResult.setResultOK(true);
			operationResult.setErrorMessage("Se han almacenado correctamente los datos biometricos");
			return new ResponseEntity<>(operationResult, HttpStatus.OK);
		} catch (FeignException fe) {
			ResponseEntity<OperationResult> resultResponseEntity = parseFeign(fe);
			if (resultResponseEntity.getBody().getErrorMessage().equals("40003")) {
				ResponseEntity<String> responseEntity = null;
				try {
					responseEntity = biometricClient.searchBySlaps(jsonRequest);
					String bodyString = responseEntity.getBody();
					JSONObject jsonObject = new JSONObject(bodyString);
					String id = jsonObject.getString("id");
					OperationResult operationResult1 = new OperationResult();
					operationResult1.setResultOK(false);
					JSONObject operationJsonResult = new JSONObject();
					operationJsonResult.put("id", Long.valueOf(id));
					operationJsonResult.put("status", "40003");
					operationResult1.setErrorMessage(operationJsonResult.toString());
					return new ResponseEntity<>(operationResult1, HttpStatus.UNPROCESSABLE_ENTITY);
				} catch (Exception e) {
					log.error("Duplicated record, unable to find related one with message: {}", e.getMessage());
					return resultResponseEntity;
				}
			} else if (resultResponseEntity.getBody().getErrorMessage().equals("40006")) {
				return new ResponseEntity<>(resultResponseEntity.getBody(), HttpStatus.PRECONDITION_FAILED);
			} else {
				return resultResponseEntity;
			}
		} catch (Exception e) {
			log.error("Error in addMinuciasSlaps for: {} - {} with message: {}", operationId, type, e.getMessage());
			operationResult.setResultOK(false);
			operationResult.setErrorMessage("40002");
			return new ResponseEntity<>(operationResult, HttpStatus.UNPROCESSABLE_ENTITY);
		}
	}
	// TODO VALIDAR RESPUESTA DEL SERVICIO 422

	@ApiOperation(value = "Adds the cyphered binary information from the capture of the fingers. Expects a JSON like {'operationId' : 1, 'scanId' : 'currentScan' , 'documentId' : 'currentDocumentManagerId' , 'll' : 'x' , 'lr' : 'x', 'lm' : 'x' , 'li': 'x', 'lt' : 'x', 'rl' : 'x', 'rr' : 'x', 'rm' : 'x', 'ri' : 'x'} as first 'l' stands for left and  'r' for right and second letter stands for little, ring, middle, index and thumb")
	@RequestMapping(value = "/minuciasCyphered", method = RequestMethod.POST, produces = "application/json;charset=utf-8")
	public ResponseEntity<OperationResult> addMinuciasCyphered(@RequestPart(value = "json") String jsonRequest,
			HttpServletRequest request) {
		log.info("INFO: " + this.getClass().getName() + ".addMinuciasCyphered:");
		OperationResult operationResult = new OperationResult();
		String username = (String) tokenUtils.getExtraInfo(request).get(TokenUtils.DETAILS_USERNAME_MAP_NAME);
		JSONObject operationRequestJSON = null;
		if (!validateJson(jsonRequest)) {
			operationResult.setErrorMessage("Bad Request");
			return new ResponseEntity<>(operationResult, HttpStatus.BAD_REQUEST);
		}
//        if (validateFingers(jsonRequest)) {
//        	log.error("ERROR:"+HttpStatus.PRECONDITION_FAILED);
//            return new ResponseEntity<>(null, HttpStatus.PRECONDITION_FAILED);
//        }
		operationRequestJSON = new JSONObject(jsonRequest);
		String type = operationRequestJSON.optString("type", "FINGERS");
		Long operationId = operationRequestJSON.getLong("operationId");
		log.info(LogUtil.logJsonObject(operationRequestJSON));
		operationRequestJSON = updateJsonRequest(operationRequestJSON, FIELDS_ALL_FINGERS);
		try {
			String jsonToSend = updateRequest(operationRequestJSON.toString());
			JSONObject jsonObject = new JSONObject(jsonToSend);
			jsonObject.put("username", username);
			ResponseEntity<String> responseEntity = biometricClient.addMinucias(jsonObject.toString(), operationId, 1);
			if (responseEntity == null) {
				log.error("ERROR: addMinucias null");
				throw new IllegalArgumentException();
			}
			log.info("INFO: addMinucias - responseEntity: StatusCode: " + responseEntity.getStatusCode() + ", Body:"
					+ responseEntity.getBody());
			if (responseEntity.getStatusCode().is4xxClientError()) {
				operationResult.setResultOK(false);
				operationResult.setErrorMessage(responseEntity.getBody());
				return new ResponseEntity<>(operationResult, responseEntity.getStatusCode());
			} else if (responseEntity.getStatusCode().is5xxServerError()) {
				operationResult.setResultOK(false);
				operationResult.setErrorMessage(responseEntity.getBody());
				return new ResponseEntity<>(operationResult, HttpStatus.UNPROCESSABLE_ENTITY);
			}
			operationResult.setResultOK(true);
			operationResult.setErrorMessage("Se han almacenado correctamente los datos biometricos");
			return new ResponseEntity<>(operationResult, HttpStatus.OK);
		} catch (FeignException fe) {
			log.error("ERROR: FeignException" + fe.getMessage());
			ResponseEntity<OperationResult> resultResponseEntity = parseFeign(fe);
			if (resultResponseEntity.getBody().getErrorMessage().equals("40003")) {
				ResponseEntity<String> responseEntity = null;
				try {
					responseEntity = biometricClient.searchByFinger(operationRequestJSON.toString());
					log.info("INFO: Realizo busqueda :" + responseEntity);
					String bodyString = responseEntity.getBody();
					JSONObject jsonObject = new JSONObject(bodyString);
					String id = jsonObject.getString("id");
					if (!(id.equals(operationId.toString()))) {
						log.info("Realizo busqueda por [" + id + "," + operationId.toString() + "]");
						String dato = biometricClient.isCorrectforce(id, operationId.toString());
						if (dato.equals("1")) {
							log.info("Realizo busqueda por [" + id + "," + operationId.toString()
									+ "] Respuesta Exitosa ");
							OperationResult operationResultF = new OperationResult();
							String usernameF = (String) tokenUtils.getExtraInfo(request)
									.get(TokenUtils.DETAILS_USERNAME_MAP_NAME);
							JSONObject operationRequestJSONF = null;
							if (!validateJson(jsonRequest)) {
								operationResultF.setErrorMessage("Bad Request");
								return new ResponseEntity<>(operationResultF, HttpStatus.BAD_REQUEST);
							}
							operationRequestJSONF = new JSONObject(jsonRequest);
							Long operationIdF = operationRequestJSONF.getLong("operationId");
							operationRequestJSONF = updateJsonRequest(operationRequestJSONF, FIELDS_ALL_FINGERS);
							try {
								String jsonToSendF = updateRequest(operationRequestJSON.toString());
								JSONObject jsonObjectForce = new JSONObject(jsonToSendF);
								jsonObjectForce.put("username", usernameF);
								ResponseEntity<String> responseEntityForce = biometricClient
										.addMinuciasForce(jsonObjectForce.toString(), operationIdF, 1);
								if (responseEntityForce == null) {
									log.error("ERROR: addMinuciasForce null");
									throw new IllegalArgumentException();
								} else if (responseEntityForce.getStatusCode().is4xxClientError()) {
									log.error("ERROR: is4xxClientError");
									operationResult.setResultOK(false);
									operationResult.setErrorMessage(responseEntity.getBody());
									return new ResponseEntity<>(operationResult, responseEntityForce.getStatusCode());
								} else if (responseEntityForce.getStatusCode().is5xxServerError()) {
									log.error("ERROR: is5xxServerError");
									operationResult.setResultOK(false);
									operationResult.setErrorMessage(responseEntity.getBody());
									return new ResponseEntity<>(operationResult, HttpStatus.UNPROCESSABLE_ENTITY);// -------------------------------
								}
								operationResult.setResultOK(true);
								operationResult
										.setErrorMessage("Se han almacenado correctamente los datos biometricos");
								return new ResponseEntity<>(operationResult, HttpStatus.OK);
							} catch (FeignException feF) {
								log.error("ERROR:" + feF.getMessage());
								OperationResult operationResult1 = new OperationResult();
								operationResult1.setResultOK(false);
								JSONObject operationJsonResult = new JSONObject();
								operationJsonResult.put("id", Long.valueOf(id));
								operationJsonResult.put("status", "40003");
								operationResult1.setErrorMessage(operationJsonResult.toString());
								return new ResponseEntity<>(operationResult1, HttpStatus.UNPROCESSABLE_ENTITY);
							}
						}
					}
					OperationResult operationResult1 = new OperationResult();
					operationResult1.setResultOK(false);
					JSONObject operationJsonResult = new JSONObject();
					operationJsonResult.put("id", Long.valueOf(id));
					operationJsonResult.put("status", "40003");
					operationResult1.setErrorMessage(operationJsonResult.toString());
					return new ResponseEntity<>(operationResult1, HttpStatus.UNPROCESSABLE_ENTITY);
				} catch (Exception e) {
					log.error("ERRO: Duplicated record, unable to find related one with message: {}", e.getMessage());
					return resultResponseEntity;
				}
			} else if (resultResponseEntity.getBody().getErrorMessage().equals("40006")) {
				log.error("ERROR:" + HttpStatus.PRECONDITION_FAILED);
				return new ResponseEntity<>(resultResponseEntity.getBody(), HttpStatus.PRECONDITION_FAILED);
			}
			{
				return resultResponseEntity;
			}
		} catch (Exception e) {
			log.error("ERROR: Error in addMinucias for: {} - {} with message: {}", operationId, type, e.getMessage());
			operationResult.setResultOK(false);
			operationResult.setErrorMessage("40002");
			return new ResponseEntity<>(operationResult, HttpStatus.UNPROCESSABLE_ENTITY);
		}

	}

	/*
	 * @ApiOperation(value =
	 * "Adds the cyphered binary information from the capture of the fingers. Expects a JSON like {'operationId' : 1, 'scanId' : 'currentScan' , 'documentId' : 'currentDocumentManagerId' , 'll' : 'x' , 'lr' : 'x', 'lm' : 'x' , 'li': 'x', 'lt' : 'x', 'rl' : 'x', 'rr' : 'x', 'rm' : 'x', 'ri' : 'x'} as first 'l' stands for left and  'r' for right and second letter stands for little, ring, middle, index and thumb"
	 * )
	 * 
	 * @RequestMapping(value = "/minuciasCyphered", method = RequestMethod.POST,
	 * produces = "application/json;charset=utf-8") public
	 * ResponseEntity<OperationResult> addMinuciasCyphered(
	 * 
	 * @RequestPart(value = "json") String jsonRequest, HttpServletRequest request)
	 * {
	 * log.info("lblancas: "+this.getClass().getName()+".{addMinuciasCyphered() }: "
	 * +jsonRequest); OperationResult operationResult = new OperationResult();
	 * JSONObject jsonObjectF=null; String username = (String)
	 * tokenUtils.getExtraInfo(request).get(TokenUtils.DETAILS_USERNAME_MAP_NAME);
	 * JSONObject operationRequestJSON = null; if (!validateJson(jsonRequest)) {
	 * operationResult.setErrorMessage("Bad Request"); return new
	 * ResponseEntity<>(operationResult, HttpStatus.BAD_REQUEST); }
	 * operationRequestJSON = new JSONObject(jsonRequest); String type =
	 * operationRequestJSON.optString("type", "FINGERS"); Long operationId =
	 * operationRequestJSON.getLong("operationId"); operationRequestJSON =
	 * updateJsonRequest(operationRequestJSON, FIELDS_ALL_FINGERS); try { String
	 * jsonToSend = updateRequest(operationRequestJSON.toString()); JSONObject
	 * jsonObject = new JSONObject(jsonToSend);
	 * 
	 * jsonObject.put("username", username);
	 * 
	 * jsonObjectF= new JSONObject(jsonToSend); jsonObjectF.put("username",
	 * username); log.info("lblancas: 1"); ResponseEntity<String> responseEntity =
	 * biometricClient.addMinucias(jsonObject.toString(), operationId, 1);
	 * log.info("lblancas: 2 "+responseEntity); if (responseEntity == null) { throw
	 * new IllegalArgumentException(); } else if
	 * (responseEntity.getStatusCode().is4xxClientError()) {
	 * operationResult.setResultOK(false);
	 * operationResult.setErrorMessage(responseEntity.getBody()); return new
	 * ResponseEntity<>(operationResult, responseEntity.getStatusCode()); } else if
	 * (responseEntity.getStatusCode().is5xxServerError()) {
	 * operationResult.setResultOK(false);
	 * operationResult.setErrorMessage(responseEntity.getBody()); return new
	 * ResponseEntity<>(operationResult, HttpStatus.UNPROCESSABLE_ENTITY); }
	 * operationResult.setResultOK(true); operationResult.
	 * setErrorMessage("Se han almacenado correctamente los datos biometricos");
	 * return new ResponseEntity<>(operationResult, HttpStatus.OK); } catch
	 * (FeignException fe) { log.info("Entro a Exception  ");
	 * ResponseEntity<OperationResult> resultResponseEntity = parseFeign(fe); if
	 * (resultResponseEntity.getBody().getErrorMessage().equals("40003")) {
	 * ResponseEntity<String> responseEntity = null; try {
	 * log.info("Invoca  biometricClient.searchByFinger "); responseEntity =
	 * biometricClient.searchByFinger(operationRequestJSON.toString());
	 * 
	 * String bodyString = responseEntity.getBody();
	 * log.info("Resultado "+bodyString); JSONObject jsonObject = new
	 * JSONObject(bodyString); String id = jsonObject.getString("id"); String
	 * responseForce = biometricClient.isCorrectforce(""+operationId,id);
	 * log.info("responseForce "+responseForce); if(responseForce.equals("1")) {
	 * log.info("responseForce >>> 1" );
	 * log.info("Json :2: "+jsonObjectF.toString());
	 * log.info("Json :3: "+operationId); ResponseEntity<String> responseEntityForce
	 * = biometricClient.addMinucias(jsonObjectF.toString(), operationId, 9);
	 * log.info("lblancas: 2 "+responseEntityForce); if (responseEntityForce ==
	 * null) { throw new IllegalArgumentException(); } else if
	 * (responseEntityForce.getStatusCode().is4xxClientError()) {
	 * operationResult.setResultOK(false);
	 * operationResult.setErrorMessage(responseEntity.getBody());
	 * log.info("lblancas: 2.e1 "+operationResult.toString()); return new
	 * ResponseEntity<>(operationResult, responseEntity.getStatusCode()); } else if
	 * (responseEntityForce.getStatusCode().is5xxServerError()) {
	 * operationResult.setResultOK(false);
	 * operationResult.setErrorMessage(responseEntity.getBody());
	 * log.info("lblancas: 2.e2 "+operationResult.toString()); return new
	 * ResponseEntity<>(operationResult, HttpStatus.UNPROCESSABLE_ENTITY); }
	 * operationResult.setResultOK(true); operationResult.
	 * setErrorMessage("Se han almacenado correctamente los datos biometricos");
	 * log.info("lblancas: 3 "+operationResult.toString()); return new
	 * ResponseEntity<>(operationResult, HttpStatus.OK); } OperationResult
	 * operationResult1 = new OperationResult();
	 * operationResult1.setResultOK(false); JSONObject operationJsonResult = new
	 * JSONObject(); operationJsonResult.put("id", Long.valueOf(id));
	 * operationJsonResult.put("status", "40003");
	 * operationResult1.setErrorMessage(operationJsonResult.toString()); return new
	 * ResponseEntity<>(operationResult1, HttpStatus.UNPROCESSABLE_ENTITY); } catch
	 * (Exception e) {
	 * log.error("Duplicated record, unable to find related one with message: {}",
	 * e.getMessage()); return resultResponseEntity; } } else if
	 * (resultResponseEntity.getBody().getErrorMessage().equals("40006")) { return
	 * new ResponseEntity<>(resultResponseEntity.getBody(),
	 * HttpStatus.PRECONDITION_FAILED); } { return resultResponseEntity; } } catch
	 * (Exception e) {
	 * log.error("Error in addMinucias for: {} - {} with message: {}", operationId,
	 * type, e.getMessage()); operationResult.setResultOK(false);
	 * operationResult.setErrorMessage("40002"); return new
	 * ResponseEntity<>(operationResult, HttpStatus.UNPROCESSABLE_ENTITY); }
	 * 
	 * }
	 */

	@Deprecated
	@ApiOperation(value = "Adds the binary information from the capture of the fingers. Expects a JSON like {'operationId' : 1, 'scanId' : 'currentScan' , 'documentId' : 'currentDocumentManagerId' , 'll' : 'x' , 'lr' : 'x', 'lm' : 'x' , 'li': 'x', 'lt' : 'x', 'rl' : 'x', 'rr' : 'x', 'rm' : 'x', 'ri' : 'x'} as first 'l' stands for left and  'r' for right and second letter stands for little, ring, middle, index and thumb")
	@RequestMapping(value = "/minucias", method = RequestMethod.POST, produces = "application/json;charset=utf-8")
	public ResponseEntity<OperationResult> addMinucias(@RequestPart(value = "json") String jsonRequest,
			HttpServletRequest request) {
		// log.info("lblancas: "+this.getClass().getName()+".{addMinucias() }");
		OperationResult operationResult = new OperationResult();
		JSONObject operationRequestJSON = null;
		String username = (String) tokenUtils.getExtraInfo(request).get(TokenUtils.DETAILS_USERNAME_MAP_NAME);
		if (!validateJson(jsonRequest)) {
			operationResult.setErrorMessage("Bad Request");
			return new ResponseEntity<>(operationResult, HttpStatus.BAD_REQUEST);
		}
		operationRequestJSON = new JSONObject(jsonRequest);
		String type = operationRequestJSON.optString("type", "FINGERS");
		Long operationId = operationRequestJSON.getLong("operationId");
		try {
			String jsonToSend = updateRequest(jsonRequest);
			JSONObject jsonObject = new JSONObject(jsonToSend);
			jsonObject.put("username", username);
			ResponseEntity<String> responseEntity = biometricClient.addMinucias(jsonObject.toString(), operationId, 1);
			if (responseEntity == null) {
				throw new IllegalArgumentException();
			} else if (responseEntity.getStatusCode().is4xxClientError()) {
				operationResult.setResultOK(false);
				operationResult.setErrorMessage(responseEntity.getBody());
				return new ResponseEntity<>(operationResult, responseEntity.getStatusCode());
			} else if (responseEntity.getStatusCode().is5xxServerError()) {
				operationResult.setResultOK(false);
				operationResult.setErrorMessage(responseEntity.getBody());
				return new ResponseEntity<>(operationResult, HttpStatus.UNPROCESSABLE_ENTITY);
			}
			operationResult.setResultOK(true);
			operationResult.setErrorMessage("Se han almacenado correctamente los datos biometricos");
			return new ResponseEntity<>(operationResult, HttpStatus.OK);
		} catch (FeignException fe) {
			log.debug("Error feign detected: {}", fe.getMessage());
			ResponseEntity<OperationResult> resultResponseEntity = parseFeign(fe);
			log.debug("Parsed feign: {}", resultResponseEntity);
			if (resultResponseEntity.getBody().getErrorMessage().equals("40003")) {
				ResponseEntity<String> responseEntity = null;
				try {
					responseEntity = biometricClient.searchByFinger(jsonRequest);
					String bodyString = responseEntity.getBody();
					JSONObject jsonObject = new JSONObject(bodyString);
					String id = jsonObject.getString("id");
					OperationResult operationResult1 = new OperationResult();
					operationResult1.setResultOK(false);
					JSONObject operationJsonResult = new JSONObject();
					operationJsonResult.put("id", Long.valueOf(id));
					operationJsonResult.put("status", "40003");
					operationResult1.setErrorMessage(operationJsonResult.toString());
					return new ResponseEntity<>(operationResult1, HttpStatus.UNPROCESSABLE_ENTITY);
				} catch (Exception e) {
					log.error("Duplicated record, unable to find related one with message: {}", e.getMessage());
					return resultResponseEntity;
				}
			} else if (resultResponseEntity.getBody().getErrorMessage().equals("40006")) {
				OperationResult operationResult1 = new OperationResult();
				operationResult1.setResultOK(false);
				operationResult1.setErrorMessage("40006");
				return new ResponseEntity<>(operationResult1, HttpStatus.PRECONDITION_FAILED);
			} else {
				return resultResponseEntity;
			}
		} catch (Exception e) {
			log.error("Error in addMinucias for: {} - {} with message: {}", operationId, type, e.getMessage());
			operationResult.setResultOK(false);
			operationResult.setErrorMessage("40002");
			return new ResponseEntity<>(operationResult, HttpStatus.UNPROCESSABLE_ENTITY);
		}
	}

	private ResponseEntity<OperationResult> parseFeign(FeignException fe) {
		// log.info("lblancas: "+this.getClass().getName()+".{parseFeign() }");
		OperationResult operationResult = new OperationResult();
		String errorTrace = fe.getMessage();
		String[] messageTrace = errorTrace.split("content:");
		if (messageTrace.length > 1) {
			String errorCode = messageTrace[messageTrace.length - 1];
			errorCode = errorCode.trim();
			try {
				Integer.parseInt(errorCode);
				operationResult.setResultOK(false);
				operationResult.setErrorMessage(errorCode);
				return new ResponseEntity<>(operationResult, HttpStatus.UNPROCESSABLE_ENTITY);
			} catch (NumberFormatException ne) {
				log.error("No valid return error code from remote service for: {}", errorCode);
			}
		}
		operationResult.setResultOK(false);
		operationResult.setErrorMessage("40002");
		return new ResponseEntity<>(operationResult, HttpStatus.UNPROCESSABLE_ENTITY);
	}

	private String updateRequest(String jsonRequest) {
		// log.info("lblancas: "+this.getClass().getName()+".{updateRequest() }");
		try {
			JSONObject jsonToSend = new JSONObject(jsonRequest);
			Long operationId = jsonToSend.getLong("operationId");
			ResponseEntity<IneDetailDTO> responseIneDetail = identificationClient.findDetail(operationId);
			IneDetailDTO ineDetailDTO = responseIneDetail.getBody();
			DocumentPictureRequestDTO pictureRequestDTO = new DocumentPictureRequestDTO();
			pictureRequestDTO.setId(String.valueOf(operationId));
			pictureRequestDTO.setIsAnverso(true);
			pictureRequestDTO.setPersonalIdentificationNumber(ineDetailDTO.getClavElec());
			ResponseEntity<byte[]> responseEntityByte = facialClient.getImageFromReference(pictureRequestDTO);
			byte[] picture = responseEntityByte.getBody();
			jsonToSend.put("facial", Base64Utils.encodeToString(picture));
			return jsonToSend.toString();
		} catch (Exception e) {
			log.error("No face retrieved with error: {}", e.getMessage());
			return jsonRequest;
		}
	}

	@ApiOperation(value = "Finds the customer related to the given cyphered biometric data", notes = "The request must be a valid json like the following: {ll,li,lm,lr,lt,rl,ri,rm,rr,rt,contentType}", response = String.class)
	@ApiResponses(value = { @ApiResponse(code = 200, message = "The customer is found"),
			@ApiResponse(code = 404, message = "The customer is not found"),
			@ApiResponse(code = 500, message = "Internal server error") })
	@RequestMapping(value = "/search/customerCyphered", method = RequestMethod.POST, produces = "application/json", consumes = "application/json")
	public ResponseEntity<String> searchByFingerCyphered(@RequestBody String jsonStringRequest) {
		// log.info("lblancas: "+this.getClass().getName()+".{searchByFingerCyphered()
		// }");
		try {
			JSONObject source = new JSONObject(jsonStringRequest);
			source = updateJsonRequest(source, FIELDS_ALL_FINGERS);
			return biometricClient.searchByFinger(source.toString());
		} catch (FeignException fe) {
			return parseFeignErrorForSearch(fe);
		} catch (Exception e) {
			log.error("Error in searchByFinger with message: {}", e.getMessage());
			return new ResponseEntity<>(buildJsonErrorSearch(), HttpStatus.UNPROCESSABLE_ENTITY);
		}
	}

	@Deprecated
	@ApiOperation(value = "Finds the customer related to the given biometric data", notes = "The request must be a valid json like the following: {ll,li,lm,lr,lt,rl,ri,rm,rr,rt,contentType}", response = String.class)
	@ApiResponses(value = { @ApiResponse(code = 200, message = "The customer is found"),
			@ApiResponse(code = 404, message = "The customer is not found"),
			@ApiResponse(code = 500, message = "Internal server error") })
	@RequestMapping(value = "/search/customer", method = RequestMethod.POST, produces = "application/json", consumes = "application/json")
	public ResponseEntity<String> searchByFinger(@RequestBody String jsonStringRequest) {
		// log.info("lblancas: "+this.getClass().getName()+".{searchByFinger() }");
		try {
			return biometricClient.searchByFinger(jsonStringRequest);
		} catch (FeignException fe) {
			return parseFeignErrorForSearch(fe);
		} catch (Exception e) {
			log.error("Error in searchByFinger with message: {}", e.getMessage());
			return new ResponseEntity<>(buildJsonErrorSearch(), HttpStatus.UNPROCESSABLE_ENTITY);
		}
	}

	@ApiOperation(value = "Finds the customer related to the given cyphered biometric data", notes = "The request must be a valid json like the following: {ll,li,lm,lr,lt,rl,ri,rm,rr,rt,contentType,id}", response = String.class)
	@ApiResponses(value = { @ApiResponse(code = 200, message = "The customer is found"),
			@ApiResponse(code = 404, message = "The customer is not found"),
			@ApiResponse(code = 500, message = "Internal server error") })
	@RequestMapping(value = "/search/customerIdCyphered", method = RequestMethod.POST, produces = "application/json", consumes = "application/json")
	public ResponseEntity<String> searchByFingerAndIdCyphered(@RequestBody String jsonStringRequest) {
		// log.info("lblancas:
		// "+this.getClass().getName()+".{searchByFingerAndIdCyphered() }
		// ::::"+jsonStringRequest);
		try {
			JSONObject source = new JSONObject(jsonStringRequest);
			source = updateJsonRequest(source, FIELDS_ALL_FINGERS);
			return biometricClient.searchByFingerAndId(source.toString());
		} catch (FeignException fe) {
			return parseFeignErrorForSearch(fe);
		} catch (Exception e) {
			log.error("Error in searchByFingerAndId with message: {}", e.getMessage());
			return new ResponseEntity<>("Error", HttpStatus.UNPROCESSABLE_ENTITY);
		}
	}

	@Deprecated
	@ApiOperation(value = "Finds the customer related to the given biometric data", notes = "The request must be a valid json like the following: {ll,li,lm,lr,lt,rl,ri,rm,rr,rt,contentType,id}", response = String.class)
	@ApiResponses(value = { @ApiResponse(code = 200, message = "The customer is found"),
			@ApiResponse(code = 404, message = "The customer is not found"),
			@ApiResponse(code = 500, message = "Internal server error") })
	@RequestMapping(value = "/search/customerId", method = RequestMethod.POST, produces = "application/json", consumes = "application/json")
	public ResponseEntity<String> searchByFingerAndId(@RequestBody String jsonStringRequest) {
		// log.info("lblancas: "+this.getClass().getName()+".{searchByFingerAndId() }");
		try {
			return biometricClient.searchByFingerAndId(jsonStringRequest);
		} catch (FeignException fe) {
			return parseFeignErrorForSearch(fe);
		} catch (Exception e) {
			log.error("Error in searchByFingerAndId with message: {}", e.getMessage());
			return new ResponseEntity<>("Error", HttpStatus.UNPROCESSABLE_ENTITY);
		}
	}

	@ApiOperation(value = "Finds the customer related to the given cyphered biometric data", notes = "The request must be a valid json like the following: {id,sl,sr,st}", response = String.class)
	@ApiResponses(value = { @ApiResponse(code = 200, message = "The customer is found"),
			@ApiResponse(code = 404, message = "The customer is not found"),
			@ApiResponse(code = 500, message = "Internal server error") })
	@RequestMapping(value = "/search/customerSlapsCyphered", method = RequestMethod.POST, produces = "application/json", consumes = "application/json")
	public ResponseEntity<String> searchBySlapsCyphered(@RequestBody String jsonStringRequest) {
		// log.info("lblancas: "+this.getClass().getName()+".{searchBySlapsCyphered()
		// }");
		try {
			JSONObject source = new JSONObject(jsonStringRequest);
			source = updateJsonRequest(source, FIELDS_SLAPS);
			return biometricClient.searchBySlaps(source.toString());
		} catch (FeignException fe) {
			return parseFeignErrorForSearch(fe);
		} catch (Exception e) {
			log.error("Error in searchBySlaps with message: {}", e.getMessage());
			return new ResponseEntity<>("Error", HttpStatus.UNPROCESSABLE_ENTITY);
		}
	}

	@Deprecated
	@ApiOperation(value = "Finds the customer related to the given biometric data", notes = "The request must be a valid json like the following: {id,sl,sr,st}", response = String.class)
	@ApiResponses(value = { @ApiResponse(code = 200, message = "The customer is found"),
			@ApiResponse(code = 404, message = "The customer is not found"),
			@ApiResponse(code = 500, message = "Internal server error") })
	@RequestMapping(value = "/search/customerSlaps", method = RequestMethod.POST, produces = "application/json", consumes = "application/json")
	public ResponseEntity<String> searchBySlaps(@RequestBody String jsonStringRequest) {
		// log.info("lblancas: "+this.getClass().getName()+".{searchBySlaps() }");
		try {
			return biometricClient.searchBySlaps(jsonStringRequest);
		} catch (FeignException fe) {
			return parseFeignErrorForSearch(fe);
		} catch (Exception e) {
			log.error("Error in searchBySlaps with message: {}", e.getMessage());
			return new ResponseEntity<>("Error", HttpStatus.UNPROCESSABLE_ENTITY);
		}
	}

	@ApiOperation(value = "Finds the customer related to the given cyphered biometric data", notes = "The request must be a valid json like the following: {id,sl,sr,st,id}", response = String.class)
	@ApiResponses(value = { @ApiResponse(code = 200, message = "The customer is found"),
			@ApiResponse(code = 404, message = "The customer is not found"),
			@ApiResponse(code = 500, message = "Internal server error") })
	@RequestMapping(value = "/search/customerSlapsIdCyphered", method = RequestMethod.POST, produces = "application/json", consumes = "application/json")
	public ResponseEntity<String> searchBySlapsIdCyphered(@RequestBody String jsonStringRequest) {
		// log.info("lblancas: "+this.getClass().getName()+".{searchBySlapsIdCyphered()
		// }");
		try {
			JSONObject source = new JSONObject(jsonStringRequest);
			source = updateJsonRequest(source, FIELDS_SLAPS);
			return biometricClient.searchBySlapsId(source.toString());
		} catch (FeignException fe) {
			return parseFeignErrorForSearch(fe);
		} catch (Exception e) {
			log.error("Error in searchBySlapsId with message: {}", e.getMessage());
			return new ResponseEntity<>("Error", HttpStatus.UNPROCESSABLE_ENTITY);
		}
	}

	@Deprecated
	@ApiOperation(value = "Finds the customer related to the given biometric data", notes = "The request must be a valid json like the following: {id,sl,sr,st,id}", response = String.class)
	@ApiResponses(value = { @ApiResponse(code = 200, message = "The customer is found"),
			@ApiResponse(code = 404, message = "The customer is not found"),
			@ApiResponse(code = 500, message = "Internal server error") })
	@RequestMapping(value = "/search/customerSlapsId", method = RequestMethod.POST, produces = "application/json", consumes = "application/json")
	public ResponseEntity<String> searchBySlapsId(@RequestBody String jsonStringRequest) {
		// log.info("lblancas: "+this.getClass().getName()+".{searchBySlapsId() }");
		try {
			return biometricClient.searchBySlapsId(jsonStringRequest);
		} catch (FeignException fe) {
			return parseFeignErrorForSearch(fe);
		} catch (Exception e) {
			log.error("Error in searchBySlapsId with message: {}", e.getMessage());
			return new ResponseEntity<>("Error", HttpStatus.UNPROCESSABLE_ENTITY);
		}
	}

	@ApiOperation(value = "Finds the customer related to the given biometric data", notes = "The request must be a valid json like the following: {id,facial}", response = String.class)
	@ApiResponses(value = { @ApiResponse(code = 200, message = "The customer is found"),
			@ApiResponse(code = 404, message = "The customer is not found"),
			@ApiResponse(code = 500, message = "Internal server error") })
	@RequestMapping(value = "/search/customerFace", method = RequestMethod.POST, produces = "application/json", consumes = "application/json")
	public ResponseEntity<String> searchByFace(@RequestBody String jsonStringRequest) {
		// log.info("lblancas: "+this.getClass().getName()+".{searchByFace() }");
		try {
			return biometricClient.searchByFace(jsonStringRequest);
		} catch (FeignException fe) {
			return parseFeignErrorForSearch(fe);
		} catch (Exception e) {
			log.error("Error in searchBySlapsId with message: {}", e.getMessage());
			return new ResponseEntity<>("Error", HttpStatus.UNPROCESSABLE_ENTITY);
		}
	}

	@ApiOperation(value = "Finds the customer related to the given biometric data", notes = "The request must be a valid json like the following: {id,facial}", response = String.class)
	@ApiResponses(value = { @ApiResponse(code = 200, message = "The customer is found"),
			@ApiResponse(code = 404, message = "The customer is not found"),
			@ApiResponse(code = 500, message = "Internal server error") })
	@RequestMapping(value = "/search/customerFaceCyphered", method = RequestMethod.POST, produces = "application/json", consumes = "application/json")
	public ResponseEntity<String> searchByFaceCyphered(@RequestBody String jsonStringRequest) {
		// log.info("lblancas: "+this.getClass().getName()+".{searchByFaceCyphered()
		// }");
		try {
			JSONObject source = new JSONObject(jsonStringRequest);
			source = updateJsonRequest(source, FIELDS_FACE);
			return biometricClient.searchByFace(source.toString());
		} catch (FeignException fe) {
			return parseFeignErrorForSearch(fe);
		} catch (Exception e) {
			log.error("Error in searchBySlapsId with message: {}", e.getMessage());
			return new ResponseEntity<>("Error", HttpStatus.UNPROCESSABLE_ENTITY);
		}
	}

	@ApiOperation(value = "Finds the customer related to the given biometric data", notes = "The request must be a valid json like the following: {id,facial}", response = String.class)
	@ApiResponses(value = { @ApiResponse(code = 200, message = "The customer is found"),
			@ApiResponse(code = 404, message = "The customer is not found"),
			@ApiResponse(code = 500, message = "Internal server error") })
	@RequestMapping(value = "/search/customerFaceId", method = RequestMethod.POST, produces = "application/json", consumes = "application/json")
	public ResponseEntity<String> searchByFaceId(@RequestBody String jsonStringRequest) {
		// log.info("lblancas: "+this.getClass().getName()+".{searchByFaceId() }");
		try {
			return biometricClient.searchByFaceId(jsonStringRequest);
		} catch (FeignException fe) {
			return parseFeignErrorForSearch(fe);
		} catch (Exception e) {
			log.error("Error in searchBySlapsId with message: {}", e.getMessage());
			return new ResponseEntity<>("Error", HttpStatus.UNPROCESSABLE_ENTITY);
		}
	}

	@ApiOperation(value = "Finds the customer related to the given biometric data", notes = "The request must be a valid json like the following: {id,facial}", response = String.class)
	@ApiResponses(value = { @ApiResponse(code = 200, message = "The customer is found"),
			@ApiResponse(code = 404, message = "The customer is not found"),
			@ApiResponse(code = 500, message = "Internal server error") })
	@RequestMapping(value = "/search/customerFaceIdCyphered", method = RequestMethod.POST, produces = "application/json", consumes = "application/json")
	public ResponseEntity<String> searchByFaceIdCyphered(@RequestBody String jsonStringRequest) {
		// log.info("lblancas: "+this.getClass().getName()+".{searchByFaceIdCyphered()
		// }");
		try {
			JSONObject source = new JSONObject(jsonStringRequest);
			source = updateJsonRequest(source, FIELDS_FACE);
			return biometricClient.searchByFaceId(source.toString());
		} catch (FeignException fe) {
			return parseFeignErrorForSearch(fe);
		} catch (Exception e) {
			log.error("Error in searchBySlapsId with message: {}", e.getMessage());
			return new ResponseEntity<>("Error", HttpStatus.UNPROCESSABLE_ENTITY);
		}
	}

	@ApiOperation(value = "Starts process on client for verification on biometric system", response = String.class, notes = "Response 1 is error, response 0 is OK")
	@RequestMapping(value = "/startAuth", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
	public String initCaptureForIdentification(@RequestBody BiometricCaptureRequestIdentDTO requestIdentDTO) {
		// log.info("lblancas:
		// "+this.getClass().getName()+".{initCaptureForIdentification() }");
		try {
			biometricClient.initCaptureForIdentification(requestIdentDTO);
			return "0";
		} catch (Exception e) {
			log.error("Error for initAuthCapture for: {} with message: {}", requestIdentDTO, e.getMessage());
		}
		return "1";
	}

	@ApiOperation(value = "Starts process on client for sign contract on biometric system", response = String.class, notes = "Response 1 is error, response 0 is OK")
	@RequestMapping(value = "/startSign", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
	public String initCaptureSignForIdentification(
			@RequestBody BiometricCaptureRequestIdentToReceiveDTO requestIdentDTO) {
		// log.info("lblancas:
		// "+this.getClass().getName()+".{initCaptureSignForIdentification() }");
		try {
			BiometricCaptureRequestIdentDTO dto = new BiometricCaptureRequestIdentDTO(requestIdentDTO.getId(),
					requestIdentDTO.getSerial(), requestIdentDTO.getUsername(), "1");
			biometricClient.initCaptureSignForIdentification(dto);
			return "0";
		} catch (Exception e) {
			log.error("Error for initAuthCapture for: {} with message: {}", requestIdentDTO, e.getMessage());
		}
		return "1";
	}

	@ApiOperation(value = "Finds the status of the process for given serial number", response = BiometricCaptureRequestIdentDTO.class)
	@RequestMapping(value = "/asignarReferencia/{id}/{idRel}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> asignarReferencia(@PathVariable String id, @PathVariable String idRel) {
		log.info("lblancas: " + this.getClass().getName() + ".asignarReferencia(" + id + "," + idRel + ")");
		try {
			String dato = biometricClient.isCorrectforce(id, idRel);
			return new ResponseEntity<>(dato, HttpStatus.OK);
		} catch (Exception e) {
			return new ResponseEntity<>((String) e.getMessage(), HttpStatus.NOT_FOUND);
		}
	}

	@ApiOperation(value = "Finds the status of the process for given serial number", response = BiometricCaptureRequestIdentDTO.class)
	@RequestMapping(value = "/statusAuth/{serial}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<BiometricCaptureRequestIdentDTO> getStatusForAuth(@PathVariable String serial) {
		// log.info("lblancas: "+this.getClass().getName()+".{getStatusForAuth() }");
		try {
			BiometricCaptureRequestIdentDTO dataFound = biometricClient.getStatusForAuth(serial);
			if (dataFound == null) {
				return new ResponseEntity<>((BiometricCaptureRequestIdentDTO) null, HttpStatus.NOT_FOUND);
			}
			return new ResponseEntity<>(dataFound, HttpStatus.OK);
		} catch (Exception e) {
			log.error("Error for getStatusForAuth for : {} with message: {}", serial, e.getMessage());
			return new ResponseEntity<>((BiometricCaptureRequestIdentDTO) null, HttpStatus.NOT_FOUND);
		}
	}

	@ApiOperation(value = "Finds the status of the process for contract sign based on given serial number", response = BiometricCaptureRequestIdentDTO.class)
	@RequestMapping(value = "/statusSign/{serial}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<BiometricCaptureRequestIdentDTO> getStatusForAuthSign(@PathVariable String serial) {
		// log.info("lblancas: "+this.getClass().getName()+".{getStatusForAuthSign()
		// }");
		try {
			BiometricCaptureRequestIdentDTO dataFound = biometricClient.getStatusForAuthSign(serial);
			if (dataFound == null) {
				return new ResponseEntity<>((BiometricCaptureRequestIdentDTO) null, HttpStatus.NOT_FOUND);
			}
			return new ResponseEntity<>(dataFound, HttpStatus.OK);
		} catch (Exception e) {
			log.error("Error for getStatusForAuth for : {} with message: {}", serial, e.getMessage());
			return new ResponseEntity<>((BiometricCaptureRequestIdentDTO) null, HttpStatus.NOT_FOUND);
		}
	}

	@ApiOperation(value = "Confirms the current capture result for the associated serial number and customer id")
	@RequestMapping(value = "/confirmAuth/{status}", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
	public String confirmCaptureAuth(@RequestBody BiometricCaptureRequestIdentToReceiveDTO requestIdentDTO,
			@PathVariable Integer status) {
		// log.info("lblancas: "+this.getClass().getName()+".{confirmCaptureAuth() }");
		try {
			BiometricCaptureRequestIdentDTO dto = new BiometricCaptureRequestIdentDTO(requestIdentDTO.getId(),
					requestIdentDTO.getSerial(), requestIdentDTO.getUsername(), "1");
			return biometricClient.confirmCaptureAuth(dto, status);
		} catch (Exception e) {
			log.error("Error on confirmCaptureAuth for: {},{} with message: {}", requestIdentDTO, status,
					e.getMessage());
			return "1";
		}
	}

	@ApiOperation(value = "Confirms the current capture result for the associated serial number and customer id for contract sign")
	@RequestMapping(value = "/confirmSign/{status}", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
	public String confirmCaptureSign(@RequestBody BiometricCaptureRequestIdentToReceiveDTO requestIdentDTO,
			@PathVariable Integer status) {
		// log.info("lblancas: "+this.getClass().getName()+".{confirmCaptureSign() }");
		try {
			BiometricCaptureRequestIdentDTO dto = new BiometricCaptureRequestIdentDTO(requestIdentDTO.getId(),
					requestIdentDTO.getSerial(), requestIdentDTO.getUsername(), "1");
			return biometricClient.confirmCaptureSign(dto, status);
		} catch (Exception e) {
			log.error("Error on confirmCaptureAuth for: {},{} with message: {}", requestIdentDTO, status,
					e.getMessage());
			return "1";
		}
	}

	@ApiOperation(value = "Queries the results for the given data")
	@RequestMapping(value = "/queryAuth", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> queryCaptureAuth(
			@RequestBody BiometricCaptureRequestIdentToReceiveDTO requestIdentDTO) {
		// log.info("lblancas: "+this.getClass().getName()+".{queryCaptureAuth() }");
		try {
			BiometricCaptureRequestIdentDTO dto = new BiometricCaptureRequestIdentDTO(requestIdentDTO.getId(),
					requestIdentDTO.getSerial(), requestIdentDTO.getUsername(), "1");
			String queryResult = biometricClient.queryCaptureAuth(dto);
			if (queryResult != null && queryResult.trim().equals("0")) {
				return new ResponseEntity<>("0", HttpStatus.NOT_FOUND);
			}
			return new ResponseEntity<>(queryResult, HttpStatus.OK);
		} catch (Exception e) {
			log.error("Erorr on queryCaptureAuth for: {} with message: {}", requestIdentDTO, e.getMessage());
			return new ResponseEntity<>("0", HttpStatus.NOT_FOUND);
		}
	}

	@ApiOperation(value = "Queries the results for the given data")
	@RequestMapping(value = "/querySign", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> queryCaptureSigm(
			@RequestBody BiometricCaptureRequestIdentToReceiveDTO requestIdentDTO) {
		// log.info("lblancas: "+this.getClass().getName()+".{queryCaptureSigm() }");
		try {
			BiometricCaptureRequestIdentDTO dto = new BiometricCaptureRequestIdentDTO(requestIdentDTO.getId(),
					requestIdentDTO.getSerial(), requestIdentDTO.getUsername(), "1");
			String queryResult = biometricClient.queryCaptureSign(dto);
			if (queryResult != null && queryResult.trim().equals("0")) {
				return new ResponseEntity<>("0", HttpStatus.NOT_FOUND);
			}
			return new ResponseEntity<>(queryResult, HttpStatus.OK);
		} catch (Exception e) {
			log.error("Erorr on queryCaptureAuth for: {} with message: {}", requestIdentDTO, e.getMessage());
			return new ResponseEntity<>("0", HttpStatus.NOT_FOUND);
		}
	}

	@ApiOperation(value = "Compares two faces according to biometric templates if it passes the requested umbral(score)")
	@RequestMapping(value = "/compareFacial", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> compareFacial(@RequestBody CompareFacialRequestDTO compareFacialRequestDTO,
			HttpServletRequest request) {
		// log.info("lblancas: "+this.getClass().getName()+".{compareFacial() }");
		try {
			JSONObject jsonObject = new JSONObject(compareFacialRequestDTO);
			log.debug("requesting: {}", jsonObject);
			ResponseEntity<String> responseEntity = biometricClient.compareFaces(jsonObject.toString());
			return responseEntity;
		} catch (Exception e) {
			log.error("Error looking for facial match with message: {}", e.getMessage());
			log.error("Trace: ", e);
			return new ResponseEntity<>((String) null, HttpStatus.NOT_FOUND);
		}
	}

	@ApiOperation(value = "Compares two faces according to biometric templates if it passes the requested umbral(score)")
	@RequestMapping(value = "/compareFacialCyphered", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> compareFacialCyphered(@RequestBody CompareFacialRequestDTO compareFacialRequestDTO,
			HttpServletRequest request) {
		// log.info("lblancas: "+this.getClass().getName()+".{ResponseEntity() }");
		try {
			String face1 = compareFacialRequestDTO.getB64Face1();
			String face2 = compareFacialRequestDTO.getB64Face2();
			face1 = decrypt.decrypt(face1);
			face2 = decrypt.decrypt(face2);
			compareFacialRequestDTO.setB64Face1(face1);
			compareFacialRequestDTO.setB64Face2(face2);
			JSONObject jsonObject = new JSONObject(compareFacialRequestDTO);
			log.debug("requesting: {}", jsonObject);
			ResponseEntity<String> responseEntity = biometricClient.compareFaces(jsonObject.toString());
			return responseEntity;
		} catch (Exception e) {
			log.error("Error looking for facial match with message: {}", e.getMessage());
			log.error("Trace: ", e);
			return new ResponseEntity<>((String) null, HttpStatus.NOT_FOUND);
		}
	}

	private ResponseEntity<String> parseFeignErrorForSearch(FeignException fe) {
		// log.info("lblancas: "+this.getClass().getName()+".{ResponseEntity() }");
		ResponseEntity<String> responseEntity;
		switch (fe.status()) {
		case 404:
			responseEntity = new ResponseEntity<>(buildJsonErrorSearch(), HttpStatus.NOT_FOUND);
			break;
		case 400:
			responseEntity = new ResponseEntity<>(buildJsonErrorSearch(), HttpStatus.BAD_REQUEST);
			break;
		case 500:
			responseEntity = new ResponseEntity<>(buildJsonErrorSearch(), HttpStatus.UNPROCESSABLE_ENTITY);
			break;
		default:
			responseEntity = new ResponseEntity<>(buildJsonErrorSearch(), HttpStatus.UNPROCESSABLE_ENTITY);
			break;
		}
		return responseEntity;
	}

	private String buildJsonErrorSearch() {
		// log.info("lblancas: "+this.getClass().getName()+".{buildJsonErrorSearch()
		// }");
		JSONObject jsonObject = new JSONObject();
		jsonObject.put("id", "0");
		jsonObject.put("hasFingers", false);
		jsonObject.put("hasFacial", false);
		jsonObject.put("time", String.valueOf(System.currentTimeMillis()));
		return jsonObject.toString();
	}

	private JSONObject updateJsonRequest(JSONObject source, String[] fields) {
		// log.info("lblancas: "+this.getClass().getName()+".{updateJsonRequest() }");
		for (String s : fields) {
			String cyphered = source.optString(s, null);
			if (cyphered != null) {
				String target = decrypt.decrypt(cyphered.replaceAll("\n",""));
				if (target == null) {
					log.error("ERROR: finger con error en decrypt: " + s);
					String target2 = decrypt.decrypt(cyphered);
					if (target2 != null) {
						source.put(s, new String(target2.replaceAll("\n","")));
					} else {
						log.error("ERROR: doble vuelta con error en decrypt: " + s);
						JSONObject error = new JSONObject();
						error.put("DECRYPT-ERROR", s);
						return error;
					}
				} else {
					source.put(s, new String(target.replaceAll("\n","")));
				}
			}
		}
		return source;
	}

	// -------------------------------------------------------------------

	public OperationResult validateFingers(String jsonRequest) {

		OperationResult res = new OperationResult();
		boolean repeated = false;
		StringBuffer repeatedFingers = new StringBuffer();
		try {
			JSONObject operationRequestJSON = new JSONObject(jsonRequest);
			log.info("###############"+operationRequestJSON.toString());
			log.info(LogUtil.logJsonObject(operationRequestJSON));
			operationRequestJSON = updateJsonRequest(operationRequestJSON, FIELDS_ALL_FINGERS);
			if(operationRequestJSON.optString("DECRYPT-ERROR").length()>0) {
				res.setErrorMessage(operationRequestJSON.toString());
				res.setResultOK(false);
				return res;
			}
			jsonRequest = operationRequestJSON.toString();
			JSONObject jsonObject = new JSONObject(jsonRequest);

//			List<byte[]> imgList = new ArrayList<byte[]>();
			Map<String, byte[]> imgList3 = new HashMap<String, byte[]>();

			if (!jsonObject.optString("li").isEmpty()) {
				File li = convertWsqToJpg(jsonObject.optString("li"));
//				imgList.add(FileUtils.readFileToByteArray(li));
				imgList3.put("li", FileUtils.readFileToByteArray(li));
				li.delete();
			}
			if (!jsonObject.optString("ll").isEmpty()) {
				File ll = convertWsqToJpg(jsonObject.optString("ll"));
//				imgList.add(FileUtils.readFileToByteArray(ll));
				imgList3.put("ll", FileUtils.readFileToByteArray(ll));
				ll.delete();
			}
			if (!jsonObject.optString("lm").isEmpty()) {
				File lm = convertWsqToJpg(jsonObject.optString("lm"));
//				imgList.add(FileUtils.readFileToByteArray(lm));
				imgList3.put("lm", FileUtils.readFileToByteArray(lm));
				lm.delete();
			}
			if (!jsonObject.optString("lr").isEmpty()) {
				File lr = convertWsqToJpg(jsonObject.optString("lr"));
//				imgList.add(FileUtils.readFileToByteArray(lr));
				imgList3.put("lr", FileUtils.readFileToByteArray(lr));
				lr.delete();

			}
			if (!jsonObject.optString("lt").isEmpty()) {
				File lt = convertWsqToJpg(jsonObject.optString("lt"));
//				imgList.add(FileUtils.readFileToByteArray(lt));
				imgList3.put("lt", FileUtils.readFileToByteArray(lt));
				lt.delete();
			}
			if (!jsonObject.optString("ri").isEmpty()) {
				File ri = convertWsqToJpg(jsonObject.optString("ri"));
//				imgList.add(FileUtils.readFileToByteArray(ri));
				imgList3.put("ri", FileUtils.readFileToByteArray(ri));
				ri.delete();
			}
			if (!jsonObject.optString("rl").isEmpty()) {
				File rl = convertWsqToJpg(jsonObject.optString("rl"));
//				imgList.add(FileUtils.readFileToByteArray(rl));
				imgList3.put("rl", FileUtils.readFileToByteArray(rl));
				rl.delete();
			}
			if (!jsonObject.optString("rm").isEmpty()) {
				File rm = convertWsqToJpg(jsonObject.optString("rm"));
//				imgList.add(FileUtils.readFileToByteArray(rm));
				imgList3.put("rm", FileUtils.readFileToByteArray(rm));
				rm.delete();
			}
			if (!jsonObject.optString("rr").isEmpty()) {
				File rr = convertWsqToJpg(jsonObject.optString("rr"));
//				imgList.add(FileUtils.readFileToByteArray(rr));
				imgList3.put("rr", FileUtils.readFileToByteArray(rr));
				rr.delete();
			}
			if (!jsonObject.optString("rt").isEmpty()) {
				File rt = convertWsqToJpg(jsonObject.optString("rt"));
//				imgList.add(FileUtils.readFileToByteArray(rt));
				imgList3.put("rt", FileUtils.readFileToByteArray(rt));
				rt.delete();
			}
			boolean result = false;
			for (Entry<String, byte[]> finger : imgList3.entrySet()) {
				for (Entry<String, byte[]> fingerList : imgList3.entrySet()) {
//					log.info(finger.getKey()+" vs "+fingerList.getKey());
					if (!finger.getKey().equals(fingerList.getKey())) {
						result = fingerImageTemplateMatch(finger.getValue(), fingerList.getValue());
//						log.info("match:" + result);
						if (result) {
							repeated = true;
							res.setResultOK(false);
							repeatedFingers.toString().replace(finger.getKey() + ",", "");
							repeatedFingers.append(finger.getKey()).append(",").append(fingerList.getKey()).append(",");

						}
					}
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		if (repeated) {
			res.setErrorMessage(repeatedFingers.toString().substring(0, repeatedFingers.toString().length() - 1));
		} else {
			res.setErrorMessage("NO REPEATED FINGERS");
			res.setResultOK(true);
		}
		log.info("result: "+repeated+" ErrorMessage:"+res.getErrorMessage());
		return res;

	}

	public boolean fingerImageTemplateMatch(byte[] probeImage, byte[] candidateImage) throws IOException {
		FingerprintTemplate probe = new FingerprintTemplate().dpi(500).create(probeImage);
		FingerprintTemplate candidate = new FingerprintTemplate().dpi(500).create(candidateImage);
		double score = new FingerprintMatcher().index(probe).match(candidate);
		double threshold = 50;
		boolean matches = score >= threshold;
//		log.info("SCORE: " + score + "   minvalue:" + threshold);
		return matches;
	}

	public static File convertWsqToJpg(String encodedImg) throws IOException {
		if (encodedImg == null || encodedImg.isEmpty())
			return null;
		File convFile = new File("tempFinger.wsq");
		byte[] encodedImgByte = Base64.getDecoder().decode(encodedImg.getBytes(StandardCharsets.UTF_8));
		FileUtils.writeByteArrayToFile(convFile, encodedImgByte);
		Date now = new Date();
		@SuppressWarnings("deprecation")
		File jpg = Jnbis.wsq().decode(convFile.getName()).toJpg().asFile("tempFinger_" + now.getDay() + ""
				+ now.getMonth() + "" + now.getYear() + "" + now.getMinutes() + ".jpg");
		convFile.delete();
		jpg.deleteOnExit();
		return jpg;
	}
}
