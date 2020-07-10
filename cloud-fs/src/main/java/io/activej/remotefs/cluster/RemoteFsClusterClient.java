/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.remotefs.cluster;

import io.activej.async.service.EventloopService;
import io.activej.bytebuf.ByteBuf;
import io.activej.common.api.WithInitializer;
import io.activej.common.collection.Try;
import io.activej.common.exception.StacklessException;
import io.activej.csp.ChannelConsumer;
import io.activej.csp.ChannelSupplier;
import io.activej.csp.dsl.ChannelConsumerTransformer;
import io.activej.csp.process.ChannelByteCombiner;
import io.activej.csp.process.ChannelSplitter;
import io.activej.eventloop.Eventloop;
import io.activej.eventloop.jmx.EventloopJmxBeanEx;
import io.activej.jmx.api.attribute.JmxAttribute;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.jmx.PromiseStats;
import io.activej.remotefs.FileMetadata;
import io.activej.remotefs.FsClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.activej.common.Preconditions.checkArgument;
import static io.activej.common.collection.CollectionUtils.transformIterator;
import static io.activej.csp.ChannelConsumer.getAcknowledgement;
import static io.activej.csp.dsl.ChannelConsumerTransformer.identity;
import static io.activej.remotefs.util.RemoteFsUtils.ofFixedSize;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

/**
 * An implementation of {@link FsClient} which operates on a map of other clients as a cluster.
 * Contains some redundancy and fail-safety capabilities.
 */
public final class RemoteFsClusterClient implements FsClient, WithInitializer<RemoteFsClusterClient>, EventloopService, EventloopJmxBeanEx {
	private static final Logger logger = LoggerFactory.getLogger(RemoteFsClusterClient.class);

	private final FsPartitions partitions;

	private int replicationCount = 1;

	// region JMX
	private final PromiseStats uploadStartPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats uploadFinishPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats appendStartPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats appendFinishPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats downloadStartPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats downloadFinishPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats listPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats infoPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats infoAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats copyPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats copyAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats movePromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats moveAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats deletePromise = PromiseStats.create(Duration.ofMinutes(5));
	private final PromiseStats deleteAllPromise = PromiseStats.create(Duration.ofMinutes(5));
	// endregion

	// region creators
	private RemoteFsClusterClient(FsPartitions partitions) {
		this.partitions = partitions;
	}

	public static RemoteFsClusterClient create(FsPartitions partitions) {
		return new RemoteFsClusterClient(partitions);
	}

	/**
	 * Sets the replication count that determines how many copies of the file should persist over the cluster.
	 */
	public RemoteFsClusterClient withReplicationCount(int replicationCount) {
		checkArgument(1 <= replicationCount && replicationCount <= partitions.getClients().size(), "Replication count cannot be less than one or more than number of clients");
		this.replicationCount = replicationCount;
		return this;
	}
	// endregion

	// region getters
	@NotNull
	@Override
	public Eventloop getEventloop() {
		return partitions.getEventloop();
	}
	// endregion

	@Override
	public Promise<ChannelConsumer<ByteBuf>> upload(@NotNull String name) {
		return doUpload(name, client -> client.upload(name), identity(), uploadStartPromise, uploadFinishPromise);
	}

	@Override
	public Promise<ChannelConsumer<ByteBuf>> upload(@NotNull String name, long size) {
		return doUpload(name, client -> client.upload(name, size), ofFixedSize(size), uploadStartPromise, uploadFinishPromise);
	}

	@Override
	public Promise<ChannelConsumer<ByteBuf>> append(@NotNull String name, long offset) {
		return doUpload(name, client -> client.append(name, offset), identity(), appendStartPromise, appendFinishPromise);
	}

	@Override
	public Promise<ChannelSupplier<ByteBuf>> download(@NotNull String name, long offset, long limit) {
		ChannelByteCombiner combiner = ChannelByteCombiner.create();
		return checkNotDead()
				.then(() -> Promises.toList(partitions.select(name).stream()
						.map(id -> {
							logger.trace("downloading file {} from {}", name, id);
							return partitions.get(id).download(name, offset, limit)
									.thenEx(partitions.wrapDeath(id))
									.whenException(e -> logger.warn("Failed to connect to a server with key " + id + " to download file " + name, e))
									.map(supplier -> supplier
											.withEndOfStream(eos -> eos
													.thenEx(partitions.wrapDeath(id))))
									.toTry();
						})))
				.then(tries -> {
					List<ChannelSupplier<ByteBuf>> successes = tries.stream()
							.filter(Try::isSuccess)
							.map(Try::get)
							.collect(toList());
					if (successes.isEmpty()) {
						return ofFailure("Could not download file '" + name + "' from any server", tries);
					}
					for (ChannelSupplier<ByteBuf> supplier : successes) {
						combiner.addInput().set(supplier);
					}
					return Promise.of(combiner.getOutput().getSupplier());
				})
				.whenComplete(downloadStartPromise.recordStats());
	}

	@Override
	public Promise<Void> copy(@NotNull String name, @NotNull String target) {
		return FsClient.super.copy(name, target)
				.whenComplete(copyPromise.recordStats());
	}

	@Override
	public Promise<Void> copyAll(Map<String, String> sourceToTarget) {
		return FsClient.super.copyAll(sourceToTarget)
				.whenComplete(copyAllPromise.recordStats());
	}

	@Override
	public Promise<Void> move(@NotNull String name, @NotNull String target) {
		return FsClient.super.move(name, target)
				.whenComplete(movePromise.recordStats());
	}

	@Override
	public Promise<Void> moveAll(Map<String, String> sourceToTarget) {
		return FsClient.super.moveAll(sourceToTarget)
				.whenComplete(moveAllPromise.recordStats());
	}

	@Override
	public Promise<Void> delete(@NotNull String name) {
		return forEachAlive(client -> client.delete(name))
				.whenComplete(deletePromise.recordStats());
	}

	@Override
	public Promise<Void> deleteAll(Set<String> toDelete) {
		return forEachAlive(client -> client.deleteAll(toDelete))
				.whenComplete(deleteAllPromise.recordStats());
	}

	@Override
	public Promise<Map<String, FileMetadata>> list(@NotNull String glob) {
		return checkNotDead()
				.then(() -> Promises.toList(
						partitions.getAliveClients().entrySet().stream()
								.map(entry -> entry.getValue().list(glob)
										.thenEx(partitions.wrapDeath(entry.getKey()))
										.toTry())))
				.then(this::checkStillNotDead)
				.map(tries -> FileMetadata.flatten(tries.stream().filter(Try::isSuccess).map(Try::get)))
				.whenComplete(listPromise.recordStats());
	}

	@Override
	public Promise<@Nullable FileMetadata> info(@NotNull String name) {
		return checkNotDead()
				.then(() -> Promises.first((metadata, e) -> metadata != null,
						partitions.select(name)
								.stream()
								.map(id -> call(id, client -> client.info(name)))
								.iterator()))
				.thenEx((meta, e) -> checkStillNotDead(meta))
				.whenComplete(infoPromise.recordStats());
	}

	@Override
	public Promise<Map<String, @NotNull FileMetadata>> infoAll(@NotNull Set<String> names) {
		if (names.isEmpty()) return Promise.of(emptyMap());

		return checkNotDead()
				.then(() -> Promise.<Map<String, @Nullable FileMetadata>>ofCallback(cb -> {
					Map<String, @Nullable FileMetadata> result = new HashMap<>();
					Promises.all(partitions.getAliveClients().entrySet()
							.stream()
							.map(entry -> entry.getValue().infoAll(names)
									.whenResult(map -> {
										if (cb.isComplete()) return;
										result.putAll(map);
										if (result.size() == names.size()) {
											cb.trySet(result);
										}
									})
									.thenEx(partitions.wrapDeath(entry.getKey()))
									.toTry()))
							.whenResult(() -> cb.trySet(result));
				}))
				.then(this::checkStillNotDead)
				.whenComplete(infoAllPromise.recordStats());
	}

	@Override
	public Promise<Void> ping() {
		return checkNotDead();
	}

	@NotNull
	@Override
	public Promise<Void> start() {
		return ping();
	}

	@NotNull
	@Override
	public Promise<Void> stop() {
		return Promise.complete();
	}

	@Override
	public String toString() {
		return "RemoteFsClusterClient{partitions=" + partitions + '}';
	}

	private Promise<Void> forEachAlive(Function<FsClient, Promise<Void>> action) {
		return checkNotDead()
				.then(() -> Promises.all(partitions.getAliveClients().entrySet().stream()
						.map(entry -> action.apply(entry.getValue())
								.thenEx(partitions.wrapDeath(entry.getKey()))
								.toTry())));
	}

	// shortcut for creating single Exception from list of possibly failed tries
	private static <T> Throwable failedTries(String message, List<Try<T>> tries) {
		StacklessException exception = new StacklessException(RemoteFsClusterClient.class, message);
		tries.stream()
				.map(Try::getExceptionOrNull)
				.filter(Objects::nonNull)
				.forEach(exception::addSuppressed);
		return exception;
	}

	private static <T, U> Promise<T> ofFailure(String message, List<Try<U>> tries) {
		return Promise.ofException(failedTries(message, tries));
	}

	private <T> Promise<List<Try<T>>> checkStillNotDead(List<Try<T>> tries) {
		Map<Object, FsClient> deadClients = partitions.getDeadClients();
		if (deadClients.size() >= replicationCount) {
			return ofFailure("There are more dead partitions than replication count(" +
					deadClients.size() + " dead, replication count is " + replicationCount + "), aborting", tries);
		}
		return Promise.of(tries);
	}

	private <T> Promise<T> checkStillNotDead(T value) {
		return checkStillNotDead(emptyList())
				.map($ -> value);
	}

	private Promise<Void> checkNotDead() {
		return checkStillNotDead(emptyList()).toVoid();
	}

	private Promise<ChannelConsumer<ByteBuf>> doUpload(
			String name,
			Function<FsClient, Promise<ChannelConsumer<ByteBuf>>> action,
			ChannelConsumerTransformer<ByteBuf, ChannelConsumer<ByteBuf>> transformer,
			PromiseStats startStats,
			PromiseStats finishStats) {
		return checkNotDead()
				.then(() -> collect(name, action))
				.then(tries -> {
					List<Container<ChannelConsumer<ByteBuf>>> successes = tries.stream()
							.filter(Try::isSuccess)
							.map(Try::get)
							.collect(toList());

					if (successes.isEmpty()) {
						return ofFailure("Couldn't connect to any partition to upload file " + name, tries);
					}

					ChannelSplitter<ByteBuf> splitter = ChannelSplitter.<ByteBuf>create().lenient();

					Promise<List<Try<Void>>> uploadResults = Promises.toList(successes.stream()
							.map(container -> getAcknowledgement(fn ->
									splitter.addOutput()
											.set(container.value.withAcknowledgement(fn)))
									.toTry()));

					if (logger.isTraceEnabled()) {
						logger.trace("uploading file {} to {}, {}", name, successes.stream()
								.map(container -> container.id.toString())
								.collect(joining(", ", "[", "]")), this);
					}

					ChannelConsumer<ByteBuf> consumer = splitter.getInput().getConsumer();

					// check number of uploads only here, so even if there were less connections
					// than replicationCount, they would still upload
					return Promise.of(consumer
							.transformWith(transformer)
							.withAcknowledgement(ack -> ack
									.then(() -> uploadResults)
									.then(ackTries -> {
										long successCount = ackTries.stream().filter(Try::isSuccess).count();
										// check number of uploads only here, so even if there were less connections
										// than replicationCount, they will still upload
										if (ackTries.size() < replicationCount) {
											return ofFailure("Didn't connect to enough partitions uploading " +
													name + ", only " + successCount + " finished uploads", ackTries);
										}
										if (successCount < replicationCount) {
											return ofFailure("Couldn't finish uploading file " +
													name + ", only " + successCount + " acknowledgements received", ackTries);
										}
										return Promise.complete();
									})
									.whenComplete(finishStats.recordStats())));
				})
				.whenComplete(startStats.recordStats());
	}

	private Promise<List<Try<Container<ChannelConsumer<ByteBuf>>>>> collect(
			String name,
			Function<FsClient, Promise<ChannelConsumer<ByteBuf>>> action
	) {
		List<Try<Container<ChannelConsumer<ByteBuf>>>> tries = new ArrayList<>();
		Iterator<Object> idIterator = partitions.select(name).iterator();
		return Promises.all(Stream.generate(() ->
				Promises.firstSuccessful(transformIterator(idIterator,
						id -> call(id, action)
								.map(consumer -> new Container<>(id,
										consumer.withAcknowledgement(ack ->
												ack.thenEx(partitions.wrapDeath(id)))))
								.whenComplete((result, e) -> tries.add(Try.of(result, e)))))
						.toTry())
				.limit(replicationCount))
				.map($ -> tries);
	}

	private <T> Promise<T> call(Object id, Function<FsClient, Promise<T>> action) {
		return call(id, ($, client) -> action.apply(client));
	}

	private <T> Promise<T> call(Object id, BiFunction<Object, FsClient, Promise<T>> action) {
		FsClient fsClient = partitions.get(id);
		if (fsClient == null) {  // marked as dead already by somebody
			return Promise.ofException(new StacklessException(RemoteFsClusterClient.class, "Client '" + id + "' is not alive"));
		}
		return action.apply(id, fsClient)
				.thenEx(partitions.wrapDeath(id));
	}

	private static class Container<T> {
		final Object id;
		final T value;

		Container(Object id, T value) {
			this.id = id;
			this.value = value;
		}
	}

	// region JMX
	@JmxAttribute
	public int getReplicationCount() {
		return replicationCount;
	}

	@JmxAttribute
	public void setReplicationCount(int replicationCount) {
		withReplicationCount(replicationCount);
	}

	@JmxAttribute
	public int getAlivePartitionCount() {
		return partitions.getAliveClients().size();
	}

	@JmxAttribute
	public int getDeadPartitionCount() {
		return partitions.getDeadClients().size();
	}

	@JmxAttribute
	public String[] getAlivePartitions() {
		return partitions.getAliveClients().keySet().stream()
				.map(Object::toString)
				.toArray(String[]::new);
	}

	@JmxAttribute
	public String[] getDeadPartitions() {
		return partitions.getDeadClients().keySet().stream()
				.map(Object::toString)
				.toArray(String[]::new);
	}

	@JmxAttribute
	public PromiseStats getUploadStartPromise() {
		return uploadStartPromise;
	}

	@JmxAttribute
	public PromiseStats getUploadFinishPromise() {
		return uploadFinishPromise;
	}

	@JmxAttribute
	public PromiseStats getAppendStartPromise() {
		return appendStartPromise;
	}

	@JmxAttribute
	public PromiseStats getAppendFinishPromise() {
		return appendFinishPromise;
	}

	@JmxAttribute
	public PromiseStats getDownloadStartPromise() {
		return downloadStartPromise;
	}

	@JmxAttribute
	public PromiseStats getDownloadFinishPromise() {
		return downloadFinishPromise;
	}

	@JmxAttribute
	public PromiseStats getListPromise() {
		return listPromise;
	}

	@JmxAttribute
	public PromiseStats getInfoPromise() {
		return infoPromise;
	}

	@JmxAttribute
	public PromiseStats getInfoAllPromise() {
		return infoAllPromise;
	}

	@JmxAttribute
	public PromiseStats getDeletePromise() {
		return deletePromise;
	}

	@JmxAttribute
	public PromiseStats getDeleteAllPromise() {
		return deleteAllPromise;
	}

	@JmxAttribute
	public PromiseStats getCopyPromise() {
		return copyPromise;
	}

	@JmxAttribute
	public PromiseStats getCopyAllPromise() {
		return copyAllPromise;
	}

	@JmxAttribute
	public PromiseStats getMovePromise() {
		return movePromise;
	}

	@JmxAttribute
	public PromiseStats getMoveAllPromise() {
		return moveAllPromise;
	}
	// endregion
}
