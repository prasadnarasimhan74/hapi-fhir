
package ca.uhn.fhir.jpa.interceptor;

/*-
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2017 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.NameValuePair;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.hl7.fhir.dstu3.model.Observation;
import org.hl7.fhir.dstu3.model.Subscription;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.jpa.dao.BaseHapiFhirDao;
import ca.uhn.fhir.jpa.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.dao.SearchParameterMap;
import ca.uhn.fhir.jpa.provider.ServletSubRequestDetails;
import ca.uhn.fhir.jpa.service.TMinusService;
import ca.uhn.fhir.jpa.thread.HttpRequestDstu3Job;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.method.RequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.Constants;
import ca.uhn.fhir.rest.server.EncodingEnum;
import ca.uhn.fhir.rest.server.IBundleProvider;
import ca.uhn.fhir.rest.server.interceptor.IServerOperationInterceptor;
import ca.uhn.fhir.rest.server.interceptor.InterceptorAdapter;

public class RestHookSubscriptionDstu3Interceptor extends InterceptorAdapter implements IServerOperationInterceptor {

	private static volatile ExecutorService executor;
	private static final Logger ourLog = LoggerFactory.getLogger(RestHookSubscriptionDstu3Interceptor.class);

	private final static int MAX_THREADS = 1;

	@Autowired
	@Qualifier("myObservationDaoDstu3")
	private IFhirResourceDao<Observation> myObservationDao;
	@Autowired
	@Qualifier("mySubscriptionDaoDstu3")
	private IFhirResourceDao<Subscription> mySubscriptionDao;
	@Autowired
	private FhirContext myCtx;
	
	private boolean notifyOnDelete = false;

	private final List<Subscription> restHookSubscriptions = new ArrayList<Subscription>();

	/**
	 * Check subscriptions and send notifications or payload
	 *
	 * @param idType
	 * @param resourceType
	 * @param theOperation 
	 */
	private void checkSubscriptions(IIdType idType, String resourceType, RestOperationTypeEnum theOperation) {
		/*
		 * SearchParameterMap map = new SearchParameterMap();
		 * // map.add("_id", new StringParam("Observation/" + idType.getIdPart()));
		 * map.add("code", new TokenParam("SNOMED-CT", "1000000050"));
		 * //map.setLoadSynchronous(true);
		 * // Include include = new Include("nothing");
		 * // map.addInclude(include);
		 * 
		 * RequestDetails req = new ServletSubRequestDetails();
		 * req.setSubRequest(true);
		 * 
		 * IBundleProvider myBundle = myObservationDao.search(map, req);
		 * Observation myObservation = myObservationDao.read(idType);
		 * 
		 * int mysize = myBundle.size();
		 * List result = myBundle.getResources(0, myBundle.size());
		 */
		for (Subscription subscription : restHookSubscriptions) {
			// see if the criteria matches the created object
			ourLog.info("subscription for " + resourceType + " with criteria " + subscription.getCriteria());
			if (resourceType != null && subscription.getCriteria() != null && !subscription.getCriteria().startsWith(resourceType)) {
				ourLog.info("Skipping subscription search for " + resourceType + " because it does not match the criteria " + subscription.getCriteria());
				continue;
			}
			// run the subscriptions query and look for matches, add the id as part of the criteria to avoid getting matches of previous resources rather than the recent resource
			String criteria = subscription.getCriteria();
			criteria += "&_id=" + idType.getResourceType() + "/" + idType.getIdPart();
			criteria = TMinusService.parseCriteria(criteria);

			IBundleProvider results = getBundleProvider(criteria);

			if (results.size() == 0) {
				continue;
			}

			// should just be one resource as it was filtered by the id
			for (IBaseResource nextBase : results.getResources(0, results.size())) {
				IAnyResource next = (IAnyResource) nextBase;
				ourLog.info("Found match: queueing rest-hook notification for resource: {}", next.getIdElement());
				HttpUriRequest request = createRequest(subscription, next, theOperation);
				if (request != null) {
					executor.submit(new HttpRequestDstu3Job(request, subscription));
				}
			}
		}
	}

	/**
	 * Creates an HTTP Post for a subscription
	 */
	private HttpUriRequest createRequest(Subscription theSubscription, IAnyResource theResource, RestOperationTypeEnum theOperation) {
		String url = theSubscription.getChannel().getEndpoint();
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		
		HttpUriRequest request = null;
		String resourceName = myCtx.getResourceDefinition(theResource).getName(); 

		String payload = theSubscription.getChannel().getPayload();
		String resourceId = theResource.getIdElement().getIdPart();
		
		// HTTP put
		if (theOperation == RestOperationTypeEnum.UPDATE && EncodingEnum.XML.equals(EncodingEnum.forContentType(payload))) {
			ourLog.info("XML payload found");
			StringEntity entity = getStringEntity(EncodingEnum.XML, theResource);
			HttpPut putRequest = new HttpPut(url + "/" + resourceName + "/" + resourceId);
			putRequest.addHeader(Constants.HEADER_CONTENT_TYPE, Constants.CT_FHIR_XML_NEW);
			putRequest.setEntity(entity);

			request = putRequest;
		}
		// HTTP put
		else if (theOperation == RestOperationTypeEnum.UPDATE && EncodingEnum.JSON.equals(EncodingEnum.forContentType(payload))) {
			ourLog.info("JSON payload found");
			StringEntity entity = getStringEntity(EncodingEnum.JSON, theResource);
			HttpPut putRequest = new HttpPut(url + "/" + resourceName + "/" + resourceId);
			putRequest.addHeader(Constants.HEADER_CONTENT_TYPE, Constants.CT_FHIR_JSON_NEW);
			putRequest.setEntity(entity);

			request = putRequest;
		}
		// HTTP POST
		else if (theOperation == RestOperationTypeEnum.CREATE && EncodingEnum.XML.equals(EncodingEnum.forContentType(payload))) {
			ourLog.info("XML payload found");
			StringEntity entity = getStringEntity(EncodingEnum.XML, theResource);
			HttpPost putRequest = new HttpPost(url + "/" + resourceName);
			putRequest.addHeader(Constants.HEADER_CONTENT_TYPE, Constants.CT_FHIR_XML_NEW);
			putRequest.setEntity(entity);

			request = putRequest;
		}
		// HTTP POST
		else if (theOperation == RestOperationTypeEnum.CREATE && EncodingEnum.JSON.equals(EncodingEnum.forContentType(payload))) {
			ourLog.info("JSON payload found");
			StringEntity entity = getStringEntity(EncodingEnum.JSON, theResource);
			HttpPost putRequest = new HttpPost(url + "/" + resourceName);
			putRequest.addHeader(Constants.HEADER_CONTENT_TYPE, Constants.CT_FHIR_JSON_NEW);
			putRequest.setEntity(entity);

			request = putRequest;
		}

		// request.addHeader("User-Agent", USER_AGENT);
		return request;
	}

	/**
	 * Search based on a query criteria
	 *
	 * @param criteria
	 * @return
	 */
	private IBundleProvider getBundleProvider(String criteria) {
		RuntimeResourceDefinition responseResourceDef = mySubscriptionDao.validateCriteriaAndReturnResourceDefinition(criteria);
		SearchParameterMap responseCriteriaUrl = BaseHapiFhirDao.translateMatchUrl(mySubscriptionDao, mySubscriptionDao.getContext(), criteria, responseResourceDef);

		RequestDetails req = new ServletSubRequestDetails();
		req.setSubRequest(true);

		IFhirResourceDao<? extends IBaseResource> responseDao = mySubscriptionDao.getDao(responseResourceDef.getImplementingClass());
		IBundleProvider responseResults = responseDao.search(responseCriteriaUrl, req);
		return responseResults;
	}

	/**
	 * Get the encoding from the criteria or return JSON encoding if its not found
	 *
	 * @param criteria
	 * @return
	 */
	private EncodingEnum getEncoding(String criteria) {
		// check criteria
		String params = criteria.substring(criteria.indexOf('?') + 1);
		List<NameValuePair> paramValues = URLEncodedUtils.parse(params, Constants.CHARSET_UTF8, '&');
		for (NameValuePair nameValuePair : paramValues) {
			if (Constants.PARAM_FORMAT.equals(nameValuePair.getName())) {
				return EncodingEnum.forContentType(nameValuePair.getValue());
			}
		}
		return EncodingEnum.JSON;
	}

	/**
	 * Get subscription from cache
	 *
	 * @param id
	 * @return
	 */
	private Subscription getLocalSubscription(String id) {
		if (id != null && !id.trim().isEmpty()) {
			int size = restHookSubscriptions.size();
			if (size > 0) {
				for (Subscription restHookSubscription : restHookSubscriptions) {
					if (id.equals(restHookSubscription.getIdElement().getIdPart())) {
						return restHookSubscription;
					}
				}
			}
		}

		return null;
	}

	/**
	 * Convert a resource into a string entity
	 *
	 * @param encoding
	 * @param anyResource
	 * @return
	 */
	private StringEntity getStringEntity(EncodingEnum encoding, IAnyResource anyResource) {
		String encoded = encoding.newParser(mySubscriptionDao.getContext()).encodeResourceToString(anyResource);

		StringEntity entity;
		if (encoded.equalsIgnoreCase(EncodingEnum.JSON.name())) {
			entity = new StringEntity(encoded, ContentType.APPLICATION_JSON);
		} else {
			entity = new StringEntity(encoded, ContentType.APPLICATION_XML);
		}

		return entity;
	}


	/**
	 * Read the existing subscriptions from the database
	 */
	public void initSubscriptions() {
		SearchParameterMap map = new SearchParameterMap();
		map.add(Subscription.SP_TYPE, new TokenParam(null, Subscription.SubscriptionChannelType.RESTHOOK.toCode()));
		map.add(Subscription.SP_STATUS, new TokenParam(null, Subscription.SubscriptionStatus.ACTIVE.toCode()));

		RequestDetails req = new ServletSubRequestDetails();
		req.setSubRequest(true);

		IBundleProvider subscriptionBundleList = mySubscriptionDao.search(map, req);
		List<IBaseResource> resourceList = subscriptionBundleList.getResources(0, subscriptionBundleList.size());

		for (IBaseResource resource : resourceList) {
			restHookSubscriptions.add((Subscription) resource);
		}
	}

	public boolean isNotifyOnDelete() {
		return notifyOnDelete;
	}

	@PostConstruct
	public void postConstruct() {
		try {
			executor = Executors.newFixedThreadPool(MAX_THREADS);
		} catch (Exception e) {
			throw new RuntimeException("Unable to get DAO from PROXY");
		}
	}

	/**
	 * Remove subscription from cache
	 *
	 * @param subscriptionId
	 */
	private void removeLocalSubscription(String subscriptionId) {
		Subscription localSubscription = getLocalSubscription(subscriptionId);
		if (localSubscription != null) {
			restHookSubscriptions.remove(localSubscription);
			ourLog.info("Subscription removed: " + subscriptionId);
		} else {
			ourLog.info("Subscription not found in local list. Subscription id: " + subscriptionId);
		}
	}

	/**
	 * Handles incoming resources. If the resource is a rest-hook subscription, it adds
	 * it to the rest-hook subscription list. Otherwise it checks to see if the resource
	 * matches any rest-hook subscriptions.
	 */
	@Override
	public void resourceCreated(RequestDetails theRequest, IBaseResource theResource) {
		IIdType idType = theResource.getIdElement();
		ourLog.info("resource created type: {}", theRequest.getResourceName());

		if (theResource instanceof Subscription) {
			Subscription subscription = (Subscription) theResource;
			if (subscription.getChannel() != null
					&& subscription.getChannel().getType() == Subscription.SubscriptionChannelType.RESTHOOK
					&& subscription.getStatus() == Subscription.SubscriptionStatus.REQUESTED) {
				subscription.setStatus(Subscription.SubscriptionStatus.ACTIVE);
				mySubscriptionDao.update(subscription);
				restHookSubscriptions.add(subscription);
				ourLog.info("Subscription was added. Id: " + subscription.getId());
			}
		} else {
			checkSubscriptions(idType, theRequest.getResourceName(), RestOperationTypeEnum.CREATE);
		}
	}

	/**
	 * Check subscriptions to see if there is a matching subscription when there is delete
	 *
	 * @param theRequestDetails
	 *           A bean containing details about the request that is about to be processed, including details such as the
	 *           resource type and logical ID (if any) and other FHIR-specific aspects of the request which have been
	 *           pulled out of the {@link HttpServletRequest servlet request}.
	 * @param theRequest
	 *           The incoming request
	 * @param theResponse
	 *           The response. Note that interceptors may choose to provide a response (i.e. by calling
	 *           {@link HttpServletResponse#getWriter()}) but in that case it is important to return <code>false</code>
	 *           to indicate that the server itself should not also provide a response.
	 */
	@Override
	public void resourceDeleted(RequestDetails theRequest, IBaseResource theResource) {
		String resourceType = theRequest.getResourceName();
		IIdType idType = theResource.getIdElement();

		if (resourceType.equals(Subscription.class.getSimpleName())) {
			String id = idType.getIdPart();
			removeLocalSubscription(id);
		} else {
			if (notifyOnDelete) {
				checkSubscriptions(idType, resourceType, RestOperationTypeEnum.DELETE);
			}
		}
	}

	/**
	 * Checks for updates to subscriptions or if an update to a resource matches
	 * a rest-hook subscription
	 */
	@Override
	public void resourceUpdated(RequestDetails theRequest, IBaseResource theResource) {
		String resourceType = theRequest.getResourceName();
		IIdType idType = theResource.getIdElement();

		ourLog.info("resource updated type: " + resourceType);

		if (theResource instanceof Subscription) {
			Subscription subscription = (Subscription) theResource;
			if (subscription.getChannel() != null && subscription.getChannel().getType() == Subscription.SubscriptionChannelType.RESTHOOK) {
				removeLocalSubscription(subscription.getIdElement().getIdPart());

				if (subscription.getStatus() == Subscription.SubscriptionStatus.ACTIVE) {
					restHookSubscriptions.add(subscription);
					ourLog.info("Subscription was updated. Id: " + subscription.getId());
				}
			}
		} else {
			checkSubscriptions(idType, resourceType, RestOperationTypeEnum.UPDATE);
		}
	}

	public void setNotifyOnDelete(boolean notifyOnDelete) {
		this.notifyOnDelete = notifyOnDelete;
	}
}