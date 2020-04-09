package org.bonitasoft.customeronboarding;

import com.bonitasoft.ut.tooling.BonitaBPMAssert;
import com.bonitasoft.ut.tooling.ProcessExecutionDriver;
import com.bonitasoft.ut.tooling.Server;
import com.bonitasoft.ut.tooling.StringEntityCustom;
import com.company.model.LoanRequest;
import com.company.model.LoanRequestAssert;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.entity.mime.InputStreamBody;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.cookie.BasicClientCookie;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.net.URLEncodedUtils;
import org.bonitasoft.engine.api.ProcessAPI;
import org.bonitasoft.engine.api.TenantAPIAccessor;
import org.bonitasoft.engine.bpm.contract.FileInputValue;
import org.bonitasoft.engine.session.APISession;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class IntegrationTest {

    public static final double TEST_LOAN_REQUEST_AMOUNT = 300000.0d;
    public static final int TEST_LOAN_REQUEST_DURATION_IN_YEARS = 15;
    public static final String TEST_FILE_NAME = "test.txt";
    public static final String LOGIN_SERVICE_URL = "/loginservice";
    public static final String USERNAME_WALTER_BATES = "walter.bates";
    public static final String PASSWORD_WALTER_BATES = "bpm";
    public static final String HTTP_AUTH_REQUEST_PARAM_USERNAME = "username";
    public static final String HTTP_AUTH_REQUEST_PARAM_PASSWORD = "password";
    public static final String X_BONITA_API_TOKEN_COOKIE = "X-Bonita-API-Token";
    public static final String JSESSIONID_COOKIE = "JSESSIONID";
    public static final String URL_SERVLET_FILE_UPLOAD = "/API/formFileUpload";
    private static final String REJECT_COMMENTS = "Credit limit exceeded";
    private static final String CUSTOMER_ON_BOARDING = "CustomerOnboarding";
    private static final String COMMERCIAL_OFFER_APPROVAL = "CommercialOfferApproval";
    private static final String PROCESSES_VERSION = "1.0.0";
    private static APISession session;

    private String bonitaToken;
    private String jsession;

    @BeforeClass
    public static void setUpClass() throws Exception {
        session = Server.httpConnect();
        ProcessAPI processAPI = TenantAPIAccessor.getProcessAPI(session);


        BonitaBPMAssert.setUp(session, processAPI);
        ProcessExecutionDriver.setUp(processAPI);
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        BonitaBPMAssert.tearDown();
        Server.logout(session);
    }

    @Before
    public void setUp() throws Exception {
        ProcessExecutionDriver.prepareServer();
    }

    @Test
    public void testHappyPath() throws Exception {
        // Create process instance
        long newCustomerOnBoardingProcessInstanceId = ProcessExecutionDriver.createProcessInstance(CUSTOMER_ON_BOARDING,
                PROCESSES_VERSION, newCustomerOnBoardingInputs());

        // Step "Validate provided information"
        BonitaBPMAssert.assertHumanTaskIsPendingAndExecute(newCustomerOnBoardingProcessInstanceId, "Validate provided information",
                reviewRequestApprovedInputs(), session.getUserId());


        // Step "Create commercial offer"
        BonitaBPMAssert.assertHumanTaskIsPendingAndExecute(newCustomerOnBoardingProcessInstanceId, "Create commercial offer",
                createCommercialOfferInputs(), session.getUserId());

        // Check process is finished
        BonitaBPMAssert.assertProcessInstanceIsFinished(newCustomerOnBoardingProcessInstanceId);

        Path tempDirWithPrefix = Files.createTempDirectory("test-temp-folder");
        Path clientBDMZip = Paths.get(tempDirWithPrefix.toString(), "clientBDM.txt");

        File clientBDMZipFile = clientBDMZip.toFile();
        try (FileOutputStream stream = new FileOutputStream(clientBDMZipFile)) {
            stream.write(TenantAPIAccessor.getTenantAdministrationAPI(session).getClientBDMZip());
        }

        ZipInputStream zipIn = new ZipInputStream(new FileInputStream(clientBDMZipFile));
        ZipEntry entry;
        do {
            entry.getName() == ""
        }

        // Check the amount of the loan request as correctly been saved and that request document has been approved
        LoanRequest loanRequest = BonitaBPMAssert.assertBusinessDataNotNull(LoanRequest.class,
                newCustomerOnBoardingProcessInstanceId, "loanRequest");
        LoanRequestAssert.assertThat(loanRequest).hasAmount(TEST_LOAN_REQUEST_AMOUNT).isDocumentApproved();
    }

    private Map<String, Serializable> newCustomerOnBoardingInputs() throws Exception {
        Map<String, Serializable> submitLoanRequestInputs = new HashMap<>();

        HashMap<String, Serializable> loanRequestInput = new HashMap<>();
        loanRequestInput.put("amount", TEST_LOAN_REQUEST_AMOUNT);
        loanRequestInput.put("durationInYears", TEST_LOAN_REQUEST_DURATION_IN_YEARS);

        submitLoanRequestInputs.put("loanRequestInput", loanRequestInput);

        //HashMap<String, String> uploadedFileInformation = uploadFile();
        submitLoanRequestInputs.put("payStubDocumentInput", new FileInputValue("theFile", "", "theContent".getBytes()));

        return submitLoanRequestInputs;
    }

    private HashMap<String, String> uploadFile() throws Exception {
        HashMap<String, String> uploadedFileInformation = null;

        authenticate();

        // Create an HTTP client
        try (final CloseableHttpClient httpclient = HttpClients.createDefault()) {
            // Create a POST request to upload the file
            final HttpPost httppost = new HttpPost(Server.DEFAULT_SERVER_URL + Server.BONITA_WEBAPP_NAME + URL_SERVLET_FILE_UPLOAD);

            // Set the cookies required to be authenticated
            BasicCookieStore cookieStore = new BasicCookieStore();
            BasicClientCookie cookie = new BasicClientCookie(X_BONITA_API_TOKEN_COOKIE, bonitaToken);
            cookie.setDomain("localhost");
            cookie.setPath("/");
            cookieStore.addCookie(cookie);
            BasicClientCookie jsessionCookie = new BasicClientCookie(JSESSIONID_COOKIE, jsession);
            jsessionCookie.setDomain("localhost");
            jsessionCookie.setPath("/");
            cookieStore.addCookie(jsessionCookie);

            // Add cookie to HTTP context
            HttpContext localContext = new BasicHttpContext();
            localContext.setAttribute(HttpClientContext.COOKIE_STORE, cookieStore);

            // Create an InputStreamBody for the file use for testing
            ClassLoader classloader = Thread.currentThread().getContextClassLoader();
            InputStream is = classloader.getResourceAsStream(TEST_FILE_NAME);
            final InputStreamBody inputStreamBody = new InputStreamBody(Objects.requireNonNull(is), ContentType.TEXT_PLAIN, TEST_FILE_NAME);

            // Create the request content with the file and the CSRF token
            final HttpEntity reqEntity = MultipartEntityBuilder.create()
                    .addPart("file", inputStreamBody)
                    .addBinaryBody("CSRFToken", bonitaToken.getBytes())
                    .build();

            // Set the content of the HTTP POST request
            httppost.setEntity(reqEntity);

            httppost.setHeader("Cookie", "X-Bonita-API-Token=" + bonitaToken);

            System.out.println("executing request " + httppost);
            try (final CloseableHttpResponse response = httpclient.execute(httppost, localContext)) {
                final HttpEntity resEntity = response.getEntity();
                if (resEntity != null) {
                    ObjectMapper mapper = new ObjectMapper();
                    TypeReference<HashMap<String, String>> typeRef
                            = new TypeReference<HashMap<String, String>>() {
                    };
                    uploadedFileInformation = mapper.readValue(resEntity.getContent(), typeRef);
                    String contentType = uploadedFileInformation.get("contentType");
                    uploadedFileInformation.put("contentType", contentType.substring(0, contentType.indexOf(';')));
                    EntityUtils.consume(resEntity);
                    for (Map.Entry<String, String> entry : uploadedFileInformation.entrySet()) {
                        String key = entry.getKey();
                        Serializable value = entry.getValue();
                        System.out.println("key: " + key + " value: " + value);
                    }
                }
            }
        }

        return uploadedFileInformation;
    }

    /**
     * Authentication on Bonita server to get X-Bonita-API-Token and JSESSIONID in order to be able to upload file using formFileUpload servlet
     *
     * @throws IOException error when reading answer
     */
    private void authenticate() throws IOException {
        bonitaToken = "";
        jsession = "";

        // Create an HTTP client that does not follow redirection (we don't want to be redirect to Bonita home page)
        // disableRedirectHandling can probably be removed by adding appropriate parameter to Bonita login URL
        try (final CloseableHttpClient httpclient = HttpClientBuilder.create().disableRedirectHandling().build()) {
            // Create an HTTP POST request for authentication
            final HttpPost httppost = new HttpPost(Server.DEFAULT_SERVER_URL + Server.BONITA_WEBAPP_NAME + LOGIN_SERVICE_URL);

            // Authentication credentials
            List<NameValuePair> nameValuePairs = new ArrayList<>();
            nameValuePairs.add(new BasicNameValuePair(HTTP_AUTH_REQUEST_PARAM_USERNAME, USERNAME_WALTER_BATES));
            nameValuePairs.add(new BasicNameValuePair(HTTP_AUTH_REQUEST_PARAM_PASSWORD, PASSWORD_WALTER_BATES));

            // We don't want to include content encoding information in the request as Bonita does not support it
            // So we use a custom StringEntity class and a custom ContentType
            httppost.setEntity(new StringEntityCustom(URLEncodedUtils.format(nameValuePairs, StandardCharsets.UTF_8), ContentType.create(ContentType.APPLICATION_FORM_URLENCODED.getMimeType(), "")));

            try (final CloseableHttpResponse response = httpclient.execute(httppost)) {
                final HttpEntity resEntity = response.getEntity();
                if (resEntity != null) {
                    // Get cookies headers from response header
                    Header[] headers = response.getHeaders("Set-Cookie");
                    // For each cookies header
                    for (Header header : headers) {
                        // Split each individual cookie
                        String[] cookies = header.getValue().split(";");
                        // For each cookie
                        for (String cookie : cookies) {
                            // It the Bonita CSRF token
                            if (cookie.startsWith(X_BONITA_API_TOKEN_COOKIE)) {
                                // Parse the cookie to get the X-Bonita-API-Token value
                                String[] tokenCookie = cookie.split("=");
                                bonitaToken = tokenCookie[1];
                            }
                            if (cookie.startsWith(JSESSIONID_COOKIE)) {
                                String[] tokenCookie = cookie.split("=");
                                jsession = tokenCookie[1];
                            }
                        }
                    }
                }
                EntityUtils.consume(resEntity);
            }
        }
    }

    private Map<String, Serializable> reviewRequestApprovedInputs() {
        Map<String, Serializable> reviewLoanRequestInputs = new HashMap<>();

        HashMap<String, Serializable> loanRequestInput = new HashMap<>();
        loanRequestInput.put("documentApproved", true);

        reviewLoanRequestInputs.put("loanRequestInput", loanRequestInput);

        return reviewLoanRequestInputs;
    }

    private Map<String, Serializable> createCommercialOfferInputs() {
        Map<String, Serializable> commercialOfferInputs = new HashMap<>();

        HashMap<String, Serializable> commercialOfferInput = new HashMap<>();
        commercialOfferInput.put("amount", TEST_LOAN_REQUEST_AMOUNT);
        commercialOfferInput.put("durationInYears", TEST_LOAN_REQUEST_DURATION_IN_YEARS + 5);
        commercialOfferInput.put("description", "Test");

        commercialOfferInputs.put("commercialOfferInput", commercialOfferInput);

        return commercialOfferInputs;

    }
/*
 @Test public void testRejectPath() throws Exception {

 // Create process instance to initialize vacation available
 ProcessExecutionDriver.createProcessInstance(COMMERCIAL_OFFER_APPROVAL, PROCESSES_VERSION);

 // Create process instance
 Map<String, Serializable> newVacationRequestInputs = newCustomerOnBoardingInputs();
 long newVacationRequestProcessInstanceId = ProcessExecutionDriver.createProcessInstance(CUSTOMER_ON_BOARDING,
 PROCESSES_VERSION, newVacationRequestInputs);

 // Check reviewRequest step is pending
 ActivityInstance pendingHumanTask = BonitaBPMAssert.assertHumanTaskIsPending(
 newVacationRequestProcessInstanceId, "Review request");

 // Check that vacation request business data has the expected value
 VacationRequest vacationRequest = BonitaBPMAssert.assertBusinessDataNotNull(VacationRequest.class,
 newVacationRequestProcessInstanceId, "vacationRequest");

 VacationRequestAssert.assertThat(vacationRequest).hasStatus(VacationRequestStatus.PENDING.getStatus())
 .hasNumberOfDays((Integer) newVacationRequestInputs.get("numberOfDaysContract"))
 .hasNewRequestProcessInstanceId(newVacationRequestProcessInstanceId)
 .hasRequesterBonitaUserId(session.getUserId())
 .hasReturnDate((LocalDate)newVacationRequestInputs.get("returnDateContract"))
 .hasStartDate((LocalDate)newVacationRequestInputs.get("startDateContract"));

 Assert.assertNull(vacationRequest.getReviewerBonitaUserId());
 Assert.assertNull(vacationRequest.getComments());

 // Execute the reviewRequest step
 ProcessExecutionDriver.executePendingHumanTask(pendingHumanTask, session.getUserId(),
 reviewRequestRefusedInputs());

 // Check process is finished
 BonitaBPMAssert.assertProcessInstanceIsFinished(newVacationRequestProcessInstanceId);

 // Check that vacation request business data as the expected value
 vacationRequest = BonitaBPMAssert.assertBusinessDataNotNull(VacationRequest.class,
 newVacationRequestProcessInstanceId, "vacationRequest");

 VacationRequestAssert.assertThat(vacationRequest).hasStatus(VacationRequestStatus.REFUSED.getStatus());

 // Check the vacation available counter
 VacationAvailable vacationAvailable = BonitaBPMAssert.assertBusinessDataNotNull(VacationAvailable.class,
 newVacationRequestProcessInstanceId, "requesterVacationAvailable");
 VacationAvailableAssert.assertThat(vacationAvailable).hasDaysAvailableCounter(10);

 }

 private Map<String, Serializable> reviewRequestRefusedInputs() {
 Map<String, Serializable> reviewRequestInputs = new HashMap<String, Serializable>();

 reviewRequestInputs.put("statusContract", VacationRequestStatus.REFUSED.getStatus());
 reviewRequestInputs.put("commentsContract", REJECT_COMMENTS);

 return reviewRequestInputs;
 }

 @Test public void testModify() throws Exception {
 // Create process instance to initialize vacation available
 ProcessExecutionDriver.createProcessInstance(COMMERCIAL_OFFER_APPROVAL, PROCESSES_VERSION);

 // Create a process instance of new vacation request process
 Map<String, Serializable> newVacationRequestInputs = newCustomerOnBoardingInputs();
 long newVacationRequestProcessInstanceId = ProcessExecutionDriver.createProcessInstance(CUSTOMER_ON_BOARDING,
 PROCESSES_VERSION, newVacationRequestInputs);

 // Check reviewRequest step is pending
 ActivityInstance pendingHumanTask = BonitaBPMAssert.assertHumanTaskIsPending(
 newVacationRequestProcessInstanceId, "Review request");

 // Check that vacation request business data has been created
 VacationRequest vacationRequest = BonitaBPMAssert.assertBusinessDataNotNull(VacationRequest.class,
 newVacationRequestProcessInstanceId, "vacationRequest");

 // Run the modify process
 Map<String, Serializable> modifyVacationRequestInputs = modifyVacationRequestInputs(vacationRequest
 .getPersistenceId().toString());
 long modifyVacationRequestProcessInstanceId = ProcessExecutionDriver.createProcessInstance(
 MODIFY_VACATION_REQUEST, PROCESSES_VERSION, modifyVacationRequestInputs);

 // Check modify process is finished
 BonitaBPMAssert.assertProcessInstanceIsFinished(modifyVacationRequestProcessInstanceId);

 // Check that vacation request business data as the expected value
 vacationRequest = BonitaBPMAssert.assertBusinessDataNotNull(VacationRequest.class,
 newVacationRequestProcessInstanceId, "vacationRequest");

 VacationRequestAssert.assertThat(vacationRequest).hasStatus(VacationRequestStatus.PENDING.getStatus())
 .hasNumberOfDays((Integer) modifyVacationRequestInputs.get("numberOfDaysContract"))
 .hasNewRequestProcessInstanceId(newVacationRequestProcessInstanceId)
 .hasRequesterBonitaUserId(session.getUserId())
 .hasReturnDate((LocalDate)modifyVacationRequestInputs.get("returnDateContract"))
 .hasStartDate((LocalDate)modifyVacationRequestInputs.get("startDateContract"));

 Assert.assertNull(vacationRequest.getReviewerBonitaUserId());
 Assert.assertNull(vacationRequest.getComments());

 // Execute the reviewRequest step
 ProcessExecutionDriver.executePendingHumanTask(pendingHumanTask, session.getUserId(),
 reviewRequestApprovedInputs());

 // Check process is finished
 BonitaBPMAssert.assertProcessInstanceIsFinished(newVacationRequestProcessInstanceId);

 // Check that vacation request business data as the expected value
 vacationRequest = BonitaBPMAssert.assertBusinessDataNotNull(VacationRequest.class,
 newVacationRequestProcessInstanceId, "vacationRequest");

 VacationRequestAssert.assertThat(vacationRequest).hasStatus(VacationRequestStatus.APPROVED.getStatus());
 Assert.assertNotNull(vacationRequest.getReviewerBonitaUserId());

 // Check the vacation available counter
 VacationAvailable vacationAvailable = BonitaBPMAssert.assertBusinessDataNotNull(VacationAvailable.class,
 newVacationRequestProcessInstanceId, "requesterVacationAvailable");
 VacationAvailableAssert.assertThat(vacationAvailable).hasDaysAvailableCounter(8);
 }

 private Map<String, Serializable> modifyVacationRequestInputs(String vacationRequestId) {
 Map<String, Serializable> modifyLeaveRequestInputs = new HashMap<String, Serializable>();

 LocalDate today = LocalDate.now();

 LocalDate in2Days = today.plusDays(2);
 LocalDate in4Days = today.plusDays(4);

 modifyLeaveRequestInputs.put("vacationRequestIdContract", vacationRequestId);
 modifyLeaveRequestInputs.put("startDateContract", in2Days);
 modifyLeaveRequestInputs.put("returnDateContract", in4Days);
 modifyLeaveRequestInputs.put("numberOfDaysContract", Integer.valueOf(2));

 return modifyLeaveRequestInputs;
 }

 @Test public void testCancel() throws Exception {
 // Create process instance to initialize vacation available
 ProcessExecutionDriver.createProcessInstance(COMMERCIAL_OFFER_APPROVAL, PROCESSES_VERSION);

 // Create a process instance of new vacation request process
 Map<String, Serializable> newVacationRequestInputs = newCustomerOnBoardingInputs();
 long newVacationRequestProcessInstanceId = ProcessExecutionDriver.createProcessInstance(CUSTOMER_ON_BOARDING,
 PROCESSES_VERSION, newVacationRequestInputs);

 // Check reviewRequest step is pending
 BonitaBPMAssert.assertHumanTaskIsPending(newVacationRequestProcessInstanceId, "Review request");

 // Check that vacation request business data has been created
 VacationRequest vacationRequest = BonitaBPMAssert.assertBusinessDataNotNull(VacationRequest.class,
 newVacationRequestProcessInstanceId, "vacationRequest");

 // Run the cancel process
 Map<String, Serializable> cancelVacationRequestInputs = cancelVacationRequestInputs(vacationRequest
 .getPersistenceId().toString());
 long cancelVacationRequestProcessInstanceId = ProcessExecutionDriver.createProcessInstance(
 CANCEL_VACATION_REQUEST, PROCESSES_VERSION, cancelVacationRequestInputs);

 // Check cancel process is finished
 BonitaBPMAssert.assertProcessInstanceIsFinished(cancelVacationRequestProcessInstanceId);

 // Check that vacation request business data as the expected value
 vacationRequest = BonitaBPMAssert.assertBusinessDataNotNull(VacationRequest.class,
 newVacationRequestProcessInstanceId, "vacationRequest");

 VacationRequestAssert.assertThat(vacationRequest).hasStatus(VacationRequestStatus.CANCELLED.getStatus())
 .hasNumberOfDays((Integer) newVacationRequestInputs.get("numberOfDaysContract"))
 .hasNewRequestProcessInstanceId(newVacationRequestProcessInstanceId)
 .hasRequesterBonitaUserId(session.getUserId())
 .hasReturnDate((LocalDate)newVacationRequestInputs.get("returnDateContract"))
 .hasStartDate((LocalDate)newVacationRequestInputs.get("startDateContract"));

 Assert.assertNull(vacationRequest.getReviewerBonitaUserId());
 Assert.assertNull(vacationRequest.getComments());

 // Check process is finished
 BonitaBPMAssert.assertProcessInstanceIsFinished(newVacationRequestProcessInstanceId);

 // Check that vacation request business data as the expected value
 vacationRequest = BonitaBPMAssert.assertBusinessDataNotNull(VacationRequest.class,
 newVacationRequestProcessInstanceId, "vacationRequest");

 Assert.assertNull(vacationRequest.getReviewerBonitaUserId());

 // Check the vacation available counter
 VacationAvailable vacationAvailable = BonitaBPMAssert.assertBusinessDataNotNull(VacationAvailable.class,
 newVacationRequestProcessInstanceId, "requesterVacationAvailable");
 VacationAvailableAssert.assertThat(vacationAvailable).hasDaysAvailableCounter(10);
 }

 private Map<String, Serializable> cancelVacationRequestInputs(String vacationRequestId) {
 Map<String, Serializable> cancelLeaveRequestInputs = new HashMap<String, Serializable>();

 cancelLeaveRequestInputs.put("vacationRequestIdContract", vacationRequestId);

 return cancelLeaveRequestInputs;
 }

 @Test public void testCancelAcceptedApproved() throws Exception {
 // Create process instance to initialize vacation available
 ProcessExecutionDriver.createProcessInstance(COMMERCIAL_OFFER_APPROVAL, PROCESSES_VERSION);

 // Create process instance
 long newVacationRequestProcessInstanceId = ProcessExecutionDriver.createProcessInstance(CUSTOMER_ON_BOARDING,
 PROCESSES_VERSION, newCustomerOnBoardingInputs());

 // Step reviewRequest
 BonitaBPMAssert.assertHumanTaskIsPendingAndExecute(newVacationRequestProcessInstanceId, "Review request",
 reviewRequestApprovedInputs(), session.getUserId());

 // Check process is finished
 BonitaBPMAssert.assertProcessInstanceIsFinished(newVacationRequestProcessInstanceId);

 // Check that vacation request business data exist
 VacationRequest vacationRequest = BonitaBPMAssert.assertBusinessDataNotNull(VacationRequest.class,
 newVacationRequestProcessInstanceId, "vacationRequest");

 // Start cancel vacation request process instance
 long cancelVacationRequestProcessInstanceId = ProcessExecutionDriver.createProcessInstance(CANCEL_VACATION_REQUEST,
 PROCESSES_VERSION, cancelVacationRequestInputs(vacationRequest.getPersistenceId().toString()));

 // Step Review cancellation
 BonitaBPMAssert.assertHumanTaskIsPendingAndExecute(cancelVacationRequestProcessInstanceId, "Review cancellation",
 reviewCancellationApprovedInputs(), session.getUserId());

 // Check cancel process is finished
 BonitaBPMAssert.assertProcessInstanceIsFinished(cancelVacationRequestProcessInstanceId);

 // Check that vacation request business data is cancelled
 vacationRequest = BonitaBPMAssert.assertBusinessDataNotNull(VacationRequest.class,
 newVacationRequestProcessInstanceId, "vacationRequest");

 VacationRequestAssert.assertThat(vacationRequest).hasStatus(VacationRequestStatus.CANCELLED.status);

 // Check the vacation available counter
 VacationAvailable vacationAvailable = BonitaBPMAssert.assertBusinessDataNotNull(VacationAvailable.class,
 newVacationRequestProcessInstanceId, "requesterVacationAvailable");
 VacationAvailableAssert.assertThat(vacationAvailable).hasDaysAvailableCounter(10);
 }

 private Map<String, Serializable> reviewCancellationApprovedInputs() {
 Map<String, Serializable> reviewCancellationApprovedInputs = new HashMap<String, Serializable>();

 reviewCancellationApprovedInputs.put("cancellationApprovedContract", Boolean.TRUE);

 return reviewCancellationApprovedInputs;
 }

 @Test public void testCancelAcceptedRefused() throws Exception {
 // Create process instance to initialize vacation available
 ProcessExecutionDriver.createProcessInstance(COMMERCIAL_OFFER_APPROVAL, PROCESSES_VERSION);

 // Create process instance
 long newVacationRequestProcessInstanceId = ProcessExecutionDriver.createProcessInstance(CUSTOMER_ON_BOARDING,
 PROCESSES_VERSION, newCustomerOnBoardingInputs());

 // Step reviewRequest
 BonitaBPMAssert.assertHumanTaskIsPendingAndExecute(newVacationRequestProcessInstanceId, "Review request",
 reviewRequestApprovedInputs(), session.getUserId());

 // Check process is finished
 BonitaBPMAssert.assertProcessInstanceIsFinished(newVacationRequestProcessInstanceId);

 // Check that vacation request business data exist
 VacationRequest vacationRequest = BonitaBPMAssert.assertBusinessDataNotNull(VacationRequest.class,
 newVacationRequestProcessInstanceId, "vacationRequest");

 // Start cancel vacation request process instance
 long cancelVacationRequestProcessInstanceId = ProcessExecutionDriver.createProcessInstance(CANCEL_VACATION_REQUEST,
 PROCESSES_VERSION, cancelVacationRequestInputs(vacationRequest.getPersistenceId().toString()));

 // Step Review cancellation
 BonitaBPMAssert.assertHumanTaskIsPendingAndExecute(cancelVacationRequestProcessInstanceId, "Review cancellation",
 reviewCancellationRefusedInputs(), session.getUserId());

 // Check cancel process is finished
 BonitaBPMAssert.assertProcessInstanceIsFinished(cancelVacationRequestProcessInstanceId);

 // Check that vacation request business data is cancelled
 vacationRequest = BonitaBPMAssert.assertBusinessDataNotNull(VacationRequest.class,
 newVacationRequestProcessInstanceId, "vacationRequest");

 VacationRequestAssert.assertThat(vacationRequest).hasStatus(VacationRequestStatus.APPROVED.status);

 // Check the vacation available counter
 VacationAvailable vacationAvailable = BonitaBPMAssert.assertBusinessDataNotNull(VacationAvailable.class,
 newVacationRequestProcessInstanceId, "requesterVacationAvailable");
 VacationAvailableAssert.assertThat(vacationAvailable).hasDaysAvailableCounter(9);
 }

 private Map<String, Serializable> reviewCancellationRefusedInputs() {
 Map<String, Serializable> reviewCancellationRefusedInputs = new HashMap<String, Serializable>();

 reviewCancellationRefusedInputs.put("cancellationApprovedContract", Boolean.FALSE);

 return reviewCancellationRefusedInputs;
 }

 enum VacationRequestStatus {
 PENDING("pending"), APPROVED("approved"), REFUSED("refused"), PROCESSING_CANCELLATION("processing cancellation"), CANCELLED(
 "cancelled");

 private String status;

 private VacationRequestStatus(String status) {
 this.status = status;
 }

 public String getStatus() {
 return status;
 }

 }
 */
}
