package com.controller.rest;

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


import feign.FeignException;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

@RestController
@RequestMapping(value = "/rest/biometric")
@CrossOrigin
public class BiometricController {



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
