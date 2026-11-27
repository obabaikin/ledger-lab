package com.ledgerlab.ledger.kafka;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.MemberToRemove;
import org.apache.kafka.clients.admin.RemoveMembersFromConsumerGroupOptions;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.UnknownMemberIdException;

/**
 * Removes from the consumer group the static members a ledger of the static-membership releases (up to v1.2.1) leaves
 * when it stops: they hold their partitions until their session times out. The move to the first release after them
 * runs it in a pod of the new image beside the old pods and names, on stdin, the instance-id prefix of each pod as soon
 * as it stops. Temporary, for that one release.
 */
public final class StaticMemberRemoval {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String USAGE = "usage: StaticMemberRemoval <bootstrap servers> <group>; then one instance-id prefix per line on stdin";

    private final Admin admin;
    private final String group;

    public StaticMemberRemoval(Admin admin, String group) {
        this.admin = admin;
        this.group = group;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 2) {
            System.err.println(USAGE);
            System.exit(2);
        }
        // The caller writes to this process's stdin until the move is done: it reads to the end whatever the broker answers.
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, args[0]))) {
            var removal = new StaticMemberRemoval(admin, args[1]);
            try {
                System.out.println("static members: " + removal.staticMembers(""));
            } catch (ExecutionException | TimeoutException e) {
                System.out.println("static members: unknown, the broker did not answer: " + e);
            }
            var input = new BufferedReader(new InputStreamReader(System.in, UTF_8));
            for (var line = input.readLine(); line != null; line = input.readLine()) {
                var prefix = line.strip();
                if (prefix.isEmpty()) {
                    continue;
                }
                try {
                    System.out.println(prefix + ": removed " + removal.remove(prefix));
                } catch (ExecutionException | TimeoutException e) {
                    System.out.println(prefix + ": failed, the broker did not answer: " + e);
                }
            }
        }
    }

    public List<String> staticMembers(String prefix) throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, ConsumerGroupDescription> groups;
        try {
            groups = admin.describeConsumerGroups(List.of(group)).all().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof GroupIdNotFoundException) {
                return List.of();
            }
            throw e;
        }
        return groups.values().stream()
            .flatMap(description -> description.members().stream())
            .map(MemberDescription::groupInstanceId)
            .flatMap(Optional::stream)
            .filter(id -> id.startsWith(prefix))
            .sorted()
            .toList();
    }

    /** The static members whose instance id starts with the prefix, removed; none is not an error. */
    public List<String> remove(String prefix) throws ExecutionException, InterruptedException, TimeoutException {
        var members = staticMembers(prefix);
        if (!members.isEmpty()) {
            var options = new RemoveMembersFromConsumerGroupOptions(members.stream().map(MemberToRemove::new).toList());
            try {
                admin.removeMembersFromConsumerGroup(group, options).all().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                // A member that left on its own between the two calls is as good as removed.
                if (!(e.getCause() instanceof UnknownMemberIdException)) {
                    throw e;
                }
            }
        }
        return members;
    }
}
