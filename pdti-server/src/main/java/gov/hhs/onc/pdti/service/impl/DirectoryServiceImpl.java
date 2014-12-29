package gov.hhs.onc.pdti.service.impl;

import gov.hhs.onc.pdti.DirectoryStandard;
import gov.hhs.onc.pdti.DirectoryStandardId;
import gov.hhs.onc.pdti.DirectoryType;
import gov.hhs.onc.pdti.DirectoryTypeId;
import gov.hhs.onc.pdti.data.DirectoryDataService;
import gov.hhs.onc.pdti.data.DirectoryDescriptor;
import gov.hhs.onc.pdti.interceptor.DirectoryInterceptorException;
import gov.hhs.onc.pdti.interceptor.DirectoryInterceptorNoOpException;
import gov.hhs.onc.pdti.service.DirectoryService;
import gov.hhs.onc.pdti.service.FederationService;
import gov.hhs.onc.pdti.service.base.AbstractDirectoryService;
import gov.hhs.onc.pdti.statistics.entity.PDTIStatisticsEntity;
import gov.hhs.onc.pdti.statistics.service.PdtiAuditService;
import gov.hhs.onc.pdti.util.DirectoryUtils;
import gov.hhs.onc.pdti.ws.api.BatchRequest;
import gov.hhs.onc.pdti.ws.api.BatchResponse;
import gov.hhs.onc.pdti.ws.api.Control;
import gov.hhs.onc.pdti.ws.api.FederatedResponseStatus;
import gov.hhs.onc.pdti.ws.api.FederatedSearchResponseData;
import gov.hhs.onc.pdti.ws.api.SearchResponse;
import gov.hhs.onc.pdti.ws.api.SearchResultEntryMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.namespace.QName;

import org.apache.commons.codec.binary.Base64;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.oxm.XmlMappingException;
import org.springframework.stereotype.Service;

@DirectoryStandard(DirectoryStandardId.IHE)
@Scope("singleton")
@Service("dirService")
public class DirectoryServiceImpl extends AbstractDirectoryService<BatchRequest, BatchResponse> implements DirectoryService<BatchRequest, BatchResponse> {

	private final static Logger LOGGER = Logger.getLogger(DirectoryServiceImpl.class);

	@Autowired
	PdtiAuditService pdtiAuditLogService;

	@Override
	public BatchResponse processRequest(BatchRequest batchReq) {
		DirectoryInterceptorNoOpException noOpException = null;
		boolean isError = false;
		String dirId = this.dirDesc.getDirectoryId();
		String wsdlUrl = this.dirDesc.getWsdlLocation().toString();
		dirStaticId = dirId;
		staticWsdlUrl = wsdlUrl;
		String reqId = DirectoryUtils.defaultRequestId(batchReq.getRequestId());
		BatchResponse batchResp = this.objectFactory.createBatchResponse();
		PDTIStatisticsEntity entity = new PDTIStatisticsEntity();
		entity.setBaseDn(dirId);
		entity.setCreationDate(new Date());
		entity.setPdRequestType("BatchRequest");

		String batchReqStr = null;
		try {
			try {
				this.interceptRequests(dirDesc, dirId, reqId, batchReq, batchResp);
				batchReqStr = this.dirJaxb2Marshaller.marshal(this.objectFactory.createBatchRequest(batchReq));
			} catch (DirectoryInterceptorNoOpException e) {
				noOpException = e;
			} catch (DirectoryInterceptorException e) {
				isError = true;
				this.addError(dirId, reqId, batchResp, e);
			} catch (Throwable th) {
				isError = true;
				this.addError(dirId, reqId, batchResp, th);
			}

			if (noOpException != null) {
				if (LOGGER.isTraceEnabled()) {
					LOGGER.trace("Skipping processing of DSML batch request (directoryId=" + dirId + ", requestId=" + reqId + "):\n" + batchReqStr,
							noOpException);
				} else if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("Skipping processing of DSML batch request (directoryId=" + dirId + ", requestId=" + reqId + ").", noOpException);
				}
			} else {
				if (LOGGER.isTraceEnabled()) {
					LOGGER.trace("Processing DSML batch request (directoryId=" + dirId + ", requestId=" + reqId + "):\n" + batchReqStr);
				} else if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("Processing DSML batch request (directoryId=" + dirId + ", requestId=" + reqId + ").");
				}

				getFederationOid("federationinfo.properties", "ihefederationoid");
				//If Federation is enabled, then a local ldap call and Federation both should happen.. 
				//In the else part only local Directory will be searched.
				if (isFederatedRequest(batchReq)) {
					if (this.dataServices != null) {
						for (DirectoryDataService<?> dataService : this.dataServices) {
							try {
								combineBatchResponses(batchResp, dataService.processData(batchReq));
							} catch (Throwable th) {
								this.addError(dirId, reqId, batchResp, th);
							}
						}
					}
					try {
						combineFederatedBatchResponses(batchResp, batchReq);
						for (BatchResponse batchRespCombineItem : this.fedService.federate(batchReq)) {
							batchResp.getBatchResponses().addAll(batchRespCombineItem.getBatchResponses());
						}
					} catch (Throwable th) {
						isError = true;
						this.addError(dirId, reqId, batchResp, th);
					}
				} else {
					// Call Local LDAP Directory...
					LOGGER.info("Inside Local Directory Call...");
					if (this.dataServices != null) {
						for (DirectoryDataService<?> dataService : this.dataServices) {
							try {
								combineBatchResponses(batchResp, dataService.processData(batchReq));
							} catch (Throwable th) {
								this.addError(dirId, reqId, batchResp, th);
							}
						}
					}
				}
			}
		} catch (XmlMappingException | IOException e) {
			isError = true;
			this.addError(dirId, reqId, batchResp, e);
		}
		try {
			this.interceptResponses(dirDesc, dirId, reqId, batchReq, batchResp);
		} catch (DirectoryInterceptorException e) {
			isError = true;
			this.addError(dirId, reqId, batchResp, e);
		}

		try {
			String batchRespStr = this.dirJaxb2Marshaller.marshal(this.objectFactory.createBatchResponse(batchResp));

			if (LOGGER.isTraceEnabled()) {
				LOGGER.trace("Processed DSML batch request (directoryId=" + dirId + ", requestId=" + reqId + ") into DSML batch response:\n" + batchRespStr);
			} else if (LOGGER.isDebugEnabled()) {
				LOGGER.debug("Processed DSML batch request (directoryId=" + dirId + ", requestId=" + reqId + ") into DSML batch response.");
			}
		} catch (XmlMappingException e) {
			isError = true;
			this.addError(dirId, reqId, batchResp, e);
		}
		if (isError) {
			entity.setStatus("Error");
		} else {
			entity.setStatus("Success");
		}
		//PdtiAuditLog pdtiAuditLogService = PdtiAuditLogImpl.getInstance();
		pdtiAuditLogService.savePdtiStatisticsEntity(entity);
		return batchResp;
	}

	private void getFederationOid(String propertiesFileName, String property) throws IOException {
		InputStream input = null;
		Properties prop = new Properties();
		try {
			input = getClass().getClassLoader().getResourceAsStream(propertiesFileName);
			prop.load(input);
			iheoid = prop.getProperty(property);
		}finally {
				if (null != input) {
					try {
						input.close();
					} catch (IOException e) {
						throw e;
					}
				}
			}
		}

		/**
		 *
		 * @param batchResp
		 * @param batchRespCombine
		 */
		private void combineFederatedBatchResponses(BatchResponse batchResp, BatchRequest batchRequest) {
			int count = batchResp.getBatchResponses().size();
			int responseCount = 0;
			Control searchResultEntryCtrl = buildSearchResultEntryMetadaCtrl(batchRequest);
			Control federatedResponseDataCtrl = buildFederatedResponseDataCtrl(batchRequest);
			while (responseCount < count) {
				if (batchResp.getBatchResponses().get(responseCount).getValue() instanceof SearchResponse) {
					((SearchResponse) batchResp.getBatchResponses().get(responseCount).getValue()).getSearchResultDone().getControl().add(federatedResponseDataCtrl);
					int entryCount = 0;
					int totalEntryCount = ((SearchResponse) batchResp.getBatchResponses().get(responseCount).getValue()).getSearchResultEntry().size();
					while (entryCount < totalEntryCount) {
						((SearchResponse) batchResp.getBatchResponses().get(responseCount).getValue()).getSearchResultEntry().get(entryCount).getControl().add(searchResultEntryCtrl);
						entryCount++;
					}
				}
				responseCount++;
			}
		}

		private static void combineBatchResponses(BatchResponse batchResp, List<BatchResponse> batchRespCombine) {
			for (BatchResponse batchRespCombineItem : batchRespCombine) {
				batchResp.getBatchResponses().addAll(batchRespCombineItem.getBatchResponses());
			}
		}

		/**
		 *
		 * @param batchRequest
		 * @return Control
		 */
		private Control buildFederatedResponseDataCtrl(BatchRequest batchRequest) {
			Control ctrl = new Control();
			ctrl.setType("1.3.6.1.4.1.19376.1.2.4.4.8");
			ctrl.setCriticality(false);

			try {
				StringWriter stringWriter = new StringWriter();
				JAXBContext jaxbContext = JAXBContext.newInstance(FederatedSearchResponseData.class);
				Marshaller jaxbMarshaller = jaxbContext.createMarshaller();

				FederatedResponseStatus oStatus = this.objectFactory.createFederatedResponseStatus();
				oStatus.setDirectoryId(dirStaticId);
				oStatus.setFederatedRequestId(iheoid);
				oStatus.setResultMessage("Success");

				FederatedSearchResponseData federatedSearchResponseData = this.objectFactory.createFederatedSearchResponseData();
				federatedSearchResponseData.setFederatedResponseStatus(oStatus);

				QName qName = new QName("gov.hhs.onc.pdti.ws.api", "federatedSearchResponseData");
				JAXBElement<FederatedSearchResponseData> root = new JAXBElement<FederatedSearchResponseData>(qName, FederatedSearchResponseData.class, federatedSearchResponseData);
				jaxbMarshaller.marshal(root, stringWriter);			
				ctrl.setControlValue(new String(Base64.encodeBase64(stringWriter.toString().getBytes())));
			} catch (JAXBException e) {
				e.printStackTrace();
			}
			return ctrl;
		}

		/**
		 *
		 * @param batchRequest
		 * @return Control
		 */
		private Control buildSearchResultEntryMetadaCtrl(BatchRequest batchRequest) {
			Control ctrl = new Control();
			ctrl.setType("1.3.6.1.4.1.19376.1.2.4.4.7");
			ctrl.setCriticality(false);
			try {
				StringWriter stringWriter = new StringWriter();
				JAXBContext jaxbContext = JAXBContext.newInstance(SearchResultEntryMetadata.class);
				Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
				SearchResultEntryMetadata searchResultEntryMetadata = this.objectFactory.createSearchResultEntryMetadata();
				searchResultEntryMetadata.setDirectoryId(dirStaticId);
				searchResultEntryMetadata.setDirectoryURI(staticWsdlUrl);

				QName qName = new QName("gov.hhs.onc.pdti.ws.api", "searchResultEntryMetadata");
				JAXBElement<SearchResultEntryMetadata> root = new JAXBElement<SearchResultEntryMetadata>(qName, SearchResultEntryMetadata.class, searchResultEntryMetadata);
				jaxbMarshaller.marshal(root, stringWriter);			
				ctrl.setControlValue(new String(Base64.encodeBase64(stringWriter.toString().getBytes())));

			} catch (JAXBException e) {
				e.printStackTrace();
			}
			return ctrl;
		}

		@Autowired
		@DirectoryStandard(DirectoryStandardId.IHE)
		@DirectoryType(DirectoryTypeId.MAIN)
		@Override
		protected void setDirectoryDescriptor(DirectoryDescriptor dirDesc) {
			this.dirDesc = dirDesc;
		}

		@Autowired
		@DirectoryStandard(DirectoryStandardId.IHE)
		@Override
		protected void setFederationService(FederationService<BatchRequest, BatchResponse> fedService) {
			this.fedService = fedService;
		}

	}
