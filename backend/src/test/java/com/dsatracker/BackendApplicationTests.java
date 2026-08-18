package com.dsatracker;

import com.dsatracker.github.GitHubConnectionRepository;
import com.dsatracker.github.GitHubExportJobRepository;
import com.dsatracker.github.GitHubProgressPushHistoryRepository;
import com.dsatracker.github.GitHubProgressPushScheduleRepository;
import com.dsatracker.github.GitHubSolutionCaptureRepository;
import com.dsatracker.github.GitHubWorkflowSaveRepository;
import com.dsatracker.jobs.JobApplicationRepository;
import com.dsatracker.jobs.JobListingRepository;
import com.dsatracker.jobs.JobProfileRepository;
import com.dsatracker.jobs.JobSourceRepository;
import com.dsatracker.repository.DailyCountRepository;
import com.dsatracker.repository.GroupMemberRepository;
import com.dsatracker.repository.GroupRepository;
import com.dsatracker.repository.GroupTargetVoteRepository;
import com.dsatracker.repository.PollStatusRepository;
import com.dsatracker.repository.SubmissionRepository;
import com.dsatracker.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
class BackendApplicationTests {

	/**
	 * The context-load smoke test deliberately runs without a real datasource
	 * (see src/test/resources/application.properties, which excludes the JDBC/JPA
	 * auto-configuration so wiring can be verified without a running MySQL database).
	 *
	 * <p>With JPA excluded, Spring Data repository beans are not created. Beans
	 * that depend on a repository — such as {@link com.dsatracker.service.BackfillService}
	 * (task 4.1) — would therefore fail to wire. Supplying a mock
	 * {@link UserRepository} lets the smoke test keep validating the rest of the
	 * application wiring (async config, adapters, backfill orchestration) without
	 * a database.
	 */
	@MockitoBean
	private UserRepository userRepository;

	/**
	 * {@link com.dsatracker.service.BackfillService} also depends on the
	 * {@link SubmissionRepository} (task 4.2, backfill persistence). With JPA
	 * excluded from the smoke test, that repository bean is likewise absent, so
	 * a mock is supplied to keep the wiring check database-free.
	 */
	@MockitoBean
	private SubmissionRepository submissionRepository;

	/**
	 * {@link com.dsatracker.service.PollingService} (tasks 5.2&ndash;5.5) depends
	 * on the {@link DailyCountRepository} for the {@code daily_counts} upsert. With
	 * JPA excluded from the smoke test that repository bean is absent, so a mock is
	 * supplied to keep the wiring check database-free.
	 */
	@MockitoBean
	private DailyCountRepository dailyCountRepository;

	/**
	 * {@link com.dsatracker.service.PollingService} (tasks 5.6&ndash;5.7) also
	 * depends on the {@link PollStatusRepository} to record per-platform poll
	 * health (last success/failure timestamps and reason). With JPA excluded from
	 * the smoke test that repository bean is absent, so a mock is supplied to keep
	 * the wiring check database-free.
	 */
	@MockitoBean
	private PollStatusRepository pollStatusRepository;

	/**
	 * {@link com.dsatracker.service.GroupService} (tasks 8.4&ndash;8.6) depends on
	 * the {@link GroupRepository} for group create/join and the leaderboard/history
	 * reads. With JPA excluded from the smoke test that repository bean is absent,
	 * so a mock is supplied to keep the wiring check database-free.
	 */
	@MockitoBean
	private GroupRepository groupRepository;

	/**
	 * {@link com.dsatracker.service.GroupService} (tasks 8.4&ndash;8.6) also depends
	 * on the {@link GroupMemberRepository} to enrol members and resolve a group's
	 * roster. With JPA excluded from the smoke test that repository bean is absent,
	 * so a mock is supplied to keep the wiring check database-free.
	 */
	@MockitoBean
	private GroupMemberRepository groupMemberRepository;

	/** Group target voting persistence is also excluded with JPA in this smoke test. */
	@MockitoBean
	private GroupTargetVoteRepository groupTargetVoteRepository;

	/** GitHub integration persistence is excluded with JPA in this smoke test. */
	@MockitoBean
	private GitHubConnectionRepository gitHubConnectionRepository;

	@MockitoBean
	private GitHubSolutionCaptureRepository gitHubSolutionCaptureRepository;

	@MockitoBean
	private GitHubExportJobRepository gitHubExportJobRepository;

	@MockitoBean
	private GitHubWorkflowSaveRepository gitHubWorkflowSaveRepository;

	@MockitoBean
	private GitHubProgressPushHistoryRepository gitHubProgressPushHistoryRepository;

	@MockitoBean
	private GitHubProgressPushScheduleRepository gitHubProgressPushScheduleRepository;

	/** Job board persistence is excluded with JPA in this smoke test. */
	@MockitoBean
	private JobListingRepository jobListingRepository;

	@MockitoBean
	private JobApplicationRepository jobApplicationRepository;

	@MockitoBean
	private JobProfileRepository jobProfileRepository;

	@MockitoBean
	private JobSourceRepository jobSourceRepository;

	@MockitoBean
	private PlatformTransactionManager transactionManager;

	/**
	 * {@link com.dsatracker.service.PollingService} (task 9.2) now publishes live
	 * leaderboard updates through the {@link SimpMessagingTemplate}. The STOMP
	 * broker config ({@link com.dsatracker.config.WebSocketConfig}) normally
	 * supplies this bean, but mocking it keeps the database-free smoke test's
	 * wiring check independent of the messaging infrastructure.
	 */
	@MockitoBean
	private SimpMessagingTemplate messagingTemplate;

	@Test
	void contextLoads() {
	}

}
