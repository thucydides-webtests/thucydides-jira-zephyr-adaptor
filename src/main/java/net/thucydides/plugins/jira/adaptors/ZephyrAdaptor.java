package net.thucydides.plugins.jira.adaptors;

import ch.lambdaj.function.convert.Converter;
import com.beust.jcommander.internal.Lists;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import net.thucydides.core.guice.Injectors;
import net.thucydides.core.model.Story;
import net.thucydides.core.model.TestOutcome;
import net.thucydides.core.model.TestResult;
import net.thucydides.core.model.TestStep;
import net.thucydides.core.reports.adaptors.TestOutcomeAdaptor;
import net.thucydides.core.util.EnvironmentVariables;
import net.thucydides.plugins.jira.client.JerseyJiraClient;
import net.thucydides.plugins.jira.domain.IssueSummary;
import net.thucydides.plugins.jira.service.JIRAConfiguration;
import net.thucydides.plugins.jira.service.SystemPropertiesJIRAConfiguration;
import org.joda.time.DateTime;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ch.lambdaj.Lambda.convert;

/**
 * Read manual test results from the JIRA Zephyr plugin.
 */
public class ZephyrAdaptor implements TestOutcomeAdaptor {

    private static final String ZEPHYR_REST_API = "rest/zephyr/1.0";

    private final static Map<String, TestResult> TEST_STATUS_MAP
            = ImmutableMap.of("PASS", TestResult.SUCCESS,
                              "FAIL", TestResult.FAILURE,
                              "WIP", TestResult.PENDING,
                              "BLOCKED", TestResult.SKIPPED,
                              "UNEXECUTED", TestResult.IGNORED);

    private final JerseyJiraClient jiraClient;
    private final String jiraProject;

    public ZephyrAdaptor() {
        this(new SystemPropertiesJIRAConfiguration(Injectors.getInjector().getInstance(EnvironmentVariables.class)));
    }

    public ZephyrAdaptor(JIRAConfiguration jiraConfiguration) {
        jiraClient = new JerseyJiraClient(jiraConfiguration.getJiraUrl(),
                                          jiraConfiguration.getJiraUser(),
                                          jiraConfiguration.getJiraPassword());
        jiraProject = jiraConfiguration.getProject();
    }

    @Override
    public List<TestOutcome> loadOutcomes() throws IOException {
        try {
            List<IssueSummary> manualTests = jiraClient.findByJQL("type=Test and project=" + jiraProject);
            return convert(manualTests, toTestOutcomes());
        } catch (JSONException e) {
            throw new IllegalArgumentException("Failed to load Zephyr manual tests", e);
        }
    }

    private Converter<IssueSummary, TestOutcome> toTestOutcomes() {
        return new Converter<IssueSummary, TestOutcome>() {

            @Override
            public TestOutcome convert(IssueSummary issue) {

                try {
                    List<IssueSummary> associatedIssues = getLabelsWithMatchingIssues(issue);
                    TestOutcome outcome = TestOutcome.forTestInStory("Manual test - " + issue.getSummary() + " (" + issue.getKey() + ")",
                                                                     storyFrom(associatedIssues));
                    outcome.setDescription(issue.getRenderedDescription());
                    outcome = outcomeWithTagsForIssues(outcome, issue, associatedIssues);
                    TestExecutionRecord testExecutionRecord = getTestExecutionRecordFor(issue.getId());

                    outcome.clearStartTime();

                    addTestStepsTo(outcome, testExecutionRecord, issue.getId());

                    if (noStepsAreDefined(outcome)) {
                        updateOverallTestOutcome(outcome, testExecutionRecord);
                    }
                    return outcome.asManualTest();
                } catch (JSONException e) {
                    throw new IllegalArgumentException(e);
                }
            }
        };
    }

    private void updateOverallTestOutcome(TestOutcome outcome, TestExecutionRecord testExecutionRecord) {
        outcome.setAnnotatedResult(testExecutionRecord.testResult);
        if (testExecutionRecord.executionDate != null) {
            outcome.setStartTime(testExecutionRecord.executionDate);
        }
    }

    private boolean noStepsAreDefined(TestOutcome outcome) {
        return outcome.getTestSteps().isEmpty();
    }

    private void addTestStepsTo(TestOutcome outcome, TestExecutionRecord testExecutionRecord, Long id) throws JSONException {

        JSONArray stepObjects = getTestStepsForId(id);

        if (hasTestSteps(stepObjects)) {
            for (int i = 0; i < stepObjects.length(); i++) {
                JSONObject step = stepObjects.getJSONObject(i);
                outcome.recordStep(fromJson(step, testExecutionRecord));
                if (testExecutionRecord.executionDate != null) {
                    outcome.setStartTime(testExecutionRecord.executionDate);
                }
            }
        }
    }

    private JSONArray getTestStepsForId(Long id) throws JSONException {
        WebTarget target = jiraClient.buildWebTargetFor(ZEPHYR_REST_API + "/teststep/" + id);
        Response response = target.request().get();
        jiraClient.checkValid(response);

        String jsonResponse = response.readEntity(String.class);
        return new JSONArray(jsonResponse);
    }

    private boolean hasTestSteps(JSONArray stepObjects) {
        return stepObjects.length() > 0;
    }

    private TestStep fromJson(JSONObject step, TestExecutionRecord testExecutionRecord) throws JSONException {
        String stepDescription = step.getString("htmlStep");
        return TestStep.forStepCalled(stepDescription).withResult(testExecutionRecord.testResult);
    }

    class TestExecutionRecord {
        public final TestResult testResult;
        public final DateTime executionDate;

        TestExecutionRecord(TestResult testResult, DateTime executionDate) {
            this.testResult = testResult;
            this.executionDate = executionDate;
        }
    }

    private TestExecutionRecord getTestExecutionRecordFor(Long id) throws JSONException {
        WebTarget target = jiraClient.buildWebTargetFor(ZEPHYR_REST_API + "/schedule").queryParam("issueId", id);
        Response response = target.request().get();
        jiraClient.checkValid(response);

        String jsonResponse = response.readEntity(String.class);
        JSONObject testSchedule = new JSONObject(jsonResponse);
        JSONArray schedules = testSchedule.getJSONArray("schedules");
        JSONObject statusMap = testSchedule.getJSONObject("status");

        if (hasTestSteps(schedules)) {
            JSONObject latestSchedule = schedules.getJSONObject(0);
            String executionStatus = latestSchedule.getString("executionStatus");
            DateTime executionDate = executionDateFor(latestSchedule);
            return new TestExecutionRecord(getTestResultFrom(executionStatus, statusMap), executionDate);
        } else {
            return new TestExecutionRecord(TestResult.PENDING, null);
        }
    }

    private DateTime executionDateFor(JSONObject latestSchedule) throws JSONException {
        if (latestSchedule.has("executedOn")) {
            return parser().parse(latestSchedule.getString("executedOn"));
        } else {
            return null;
        }
    }

    private ZephyrDateParser parser() {
        return new ZephyrDateParser(new DateTime());
    }

    private TestResult getTestResultFrom(String executionStatus, JSONObject statusMap) throws JSONException {
        JSONObject status = statusMap.getJSONObject(executionStatus);
        if (status != null) {
            String statusName = status.getString("name");
            if (TEST_STATUS_MAP.containsKey(statusName)) {
                return TEST_STATUS_MAP.get(statusName);
            }
        }
        return TestResult.PENDING;
    }

    private TestOutcome outcomeWithTagsForIssues(TestOutcome outcome, IssueSummary issueCard, List<IssueSummary> associatedIssues) {

        List<String> issueKeys = Lists.newArrayList();
        for(IssueSummary issue : associatedIssues) {
            issueKeys.add(issue.getKey());
        }
        return outcome.withIssues(issueKeys);
    }

    private Story storyFrom(List<IssueSummary> associatedIssues) {
        Optional<Story> associatedStory = storyAssociatedByLabels(associatedIssues);
        return associatedStory.or(Story.called("Manual tests"));
    }

    private Optional<Story> storyAssociatedByLabels(List<IssueSummary> associatedIssues) {
        if (!associatedIssues.isEmpty()) {
            return Optional.of(Story.called(associatedIssues.get(0).getSummary()));
        }
        return Optional.absent();
    }

    private List<IssueSummary> getLabelsWithMatchingIssues(IssueSummary issue) throws JSONException {
        List<IssueSummary> matchingIssues = Lists.newArrayList();
        for(String label : issue.getLabels()) {
            matchingIssues.addAll(issueWithKey(label).asSet());
        }
        return ImmutableList.copyOf(matchingIssues);
    }

    private Map<String, Optional<IssueSummary>> issueSummaryCache = Maps.newConcurrentMap();

    private Optional<IssueSummary> issueWithKey(String key) throws JSONException {
        if (issueSummaryCache.containsKey(key)) {
            return issueSummaryCache.get(key);
        } else {
            Optional<IssueSummary> issueSummary = jiraClient.findByKey(key);
            issueSummaryCache.put(key, issueSummary);
            return issueSummary;
        }
    }


    @Override
    public List<TestOutcome> loadOutcomesFrom(File file) throws IOException {
        return loadOutcomes();
    }
}
