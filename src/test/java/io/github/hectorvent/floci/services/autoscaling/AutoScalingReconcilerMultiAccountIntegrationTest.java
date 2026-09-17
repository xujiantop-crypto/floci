package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@QuarkusTest
class AutoScalingReconcilerMultiAccountIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "222222222222";
    private static final String IMAGE_ID = "ami-autoscaling-multi-account";

    @Inject
    AutoScalingService asgService;

    @Inject
    RequestContext requestContext;

    @InjectMock
    Ec2Service ec2Service;

    @Test
    void reconcilerLaunchesCapacityInTheOwningNonDefaultAccount() {
        reset(ec2Service);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String launchConfigurationName = "multi-account-lc-" + suffix;
        String groupName = "multi-account-asg-" + suffix;
        AtomicReference<String> launchAccount = new AtomicReference<>();
        Instance instance = new Instance();
        instance.setInstanceId("i-" + suffix);
        instance.setState(InstanceState.running());
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(instance));

        doAnswer(invocation -> {
            if (IMAGE_ID.equals(invocation.getArgument(1))) {
                launchAccount.set(requestContext.getAccountId());
            }
            return reservation;
        }).when(ec2Service).runInstances(
                any(), any(), any(), anyInt(), anyInt(), any(), anyList(),
                any(), any(), anyList(), any(), any(), any());
        when(ec2Service.describeInstances(eq(REGION), anyList(), isNull()))
                .thenReturn(List.of(reservation));

        try {
            RequestScopes.runAs(OTHER_ACCOUNT, () -> {
                asgService.createLaunchConfiguration(
                        REGION, launchConfigurationName, null, IMAGE_ID, "t3.micro",
                        null, List.of(), null, null, null);
                asgService.createAutoScalingGroup(
                        REGION, groupName, launchConfigurationName,
                        null, null, null, null,
                        1, 1, 1, 300,
                        List.of("us-east-1a"), List.of(), List.of(), List.of(),
                        "EC2", 0, List.of("Default"),
                        Map.of(), Map.of(), AsgOptionalFields.none());
            });

            assertTrue(RequestScopes.callAs(DEFAULT_ACCOUNT,
                    () -> asgService.describeAutoScalingGroups(REGION, List.of(groupName))).isEmpty());

            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> {
                        AutoScalingGroup reconciled = RequestScopes.callAs(OTHER_ACCOUNT,
                                () -> asgService.describeAutoScalingGroups(
                                        REGION, List.of(groupName)).getFirst());
                        assertEquals(OTHER_ACCOUNT, launchAccount.get());
                        assertEquals(1, reconciled.getInstances().size());
                    });
            assertTrue(RequestScopes.callAs(DEFAULT_ACCOUNT,
                    () -> asgService.describeAutoScalingGroups(REGION, List.of(groupName))).isEmpty());
        } finally {
            RequestScopes.runAs(OTHER_ACCOUNT, () -> {
                if (!asgService.describeAutoScalingGroups(REGION, List.of(groupName)).isEmpty()) {
                    asgService.deleteAutoScalingGroup(REGION, groupName, true);
                }
                if (!asgService.describeLaunchConfigurations(REGION, List.of(launchConfigurationName)).isEmpty()) {
                    asgService.deleteLaunchConfiguration(REGION, launchConfigurationName);
                }
            });
        }
    }
}
