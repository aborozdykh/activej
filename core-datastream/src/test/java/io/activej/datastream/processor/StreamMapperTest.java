package io.activej.datastream.processor;

import io.activej.common.exception.ExpectedException;
import io.activej.datastream.StreamConsumer;
import io.activej.datastream.StreamConsumerToList;
import io.activej.datastream.StreamSupplier;
import io.activej.promise.Promise;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static io.activej.datastream.StreamSupplier.concat;
import static io.activej.datastream.TestStreamTransformers.*;
import static io.activej.datastream.TestUtils.assertClosedWithError;
import static io.activej.datastream.TestUtils.assertEndOfStream;
import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class StreamMapperTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@Test
	public void testFunction() {
		StreamSupplier<Integer> supplier = StreamSupplier.of(1, 2, 3);
		StreamConsumerToList<Integer> consumer = StreamConsumerToList.create();
		StreamMapper<Integer, Integer> mapper = StreamMapper.create(input -> input * input);

		await(supplier.transformWith(mapper)
				.streamTo(consumer.transformWith(oneByOne())));

		assertEquals(asList(1, 4, 9), consumer.getList());

		assertEndOfStream(supplier);
		assertEndOfStream(mapper);
		assertEndOfStream(consumer);
	}

	@Test
	public void testFunctionConsumerError() {
		StreamMapper<Integer, Integer> mapper = StreamMapper.create(input -> input * input);

		List<Integer> list = new ArrayList<>();
		StreamSupplier<Integer> source1 = StreamSupplier.of(1, 2, 3);
		StreamConsumerToList<Integer> consumer = StreamConsumerToList.create(list);
		ExpectedException exception = new ExpectedException("Test Exception");

		Throwable e = awaitException(source1.transformWith(mapper)
				.streamTo(consumer
						.transformWith(decorate(promise -> promise.then(
								item -> item == 2 * 2 ? Promise.ofException(exception) : Promise.of(item))))));

		assertSame(exception, e);
		assertEquals(asList(1, 4), list);

		assertClosedWithError(source1);
		assertClosedWithError(consumer);
		assertClosedWithError(mapper);
	}

	@Test
	public void testFunctionSupplierError() {
		StreamMapper<Integer, Integer> mapper = StreamMapper.create(input -> input * input);

		ExpectedException exception = new ExpectedException("Test Exception");
		StreamSupplier<Integer> supplier = concat(
				StreamSupplier.of(1, 2, 3),
				StreamSupplier.of(4, 5, 6),
				StreamSupplier.closingWithError(exception));

		StreamConsumerToList<Integer> consumer = StreamConsumerToList.create();

		Throwable e = awaitException(supplier.transformWith(mapper)
				.streamTo(consumer));

		assertSame(exception, e);
		assertEquals(asList(1, 4, 9, 16, 25, 36), consumer.getList());

		assertClosedWithError(consumer);
		assertClosedWithError(mapper);
	}

	@Test
	public void testIdentity() {
		Function<String, String> function1 = Function.identity();
		Function<Integer, Integer> function2 = Function.identity();
		assertSame(function1, function2);
	}

	@Test
	public void testMappedConsumer() {
		StreamSupplier<Integer> supplier = StreamSupplier.of(1, 2, 3);
		StreamConsumerToList<Integer> consumer = StreamConsumerToList.create();
		StreamMapper<Integer, Integer> mapper = StreamMapper.create(input -> input * input);

		StreamConsumer<Integer> mappedConsumer = consumer
				.transformWith(mapper)
				.transformWith(oneByOne());

		await(supplier.streamTo(mappedConsumer));

		assertEquals(asList(1, 4, 9), consumer.getList());

		assertEndOfStream(supplier);
		assertEndOfStream(mapper);
		assertEndOfStream(consumer);
	}

	@Test
	public void testConsecutiveMappers() {
		StreamSupplier<Integer> supplier = StreamSupplier.of(1, 2, 3, 4, 5, 6);
		StreamConsumerToList<Integer> consumer = StreamConsumerToList.create();
		StreamMapper<Integer, Integer> squareMapper = StreamMapper.create(input -> input * input);
		StreamMapper<Integer, Integer> doubleMapper = StreamMapper.create(input -> input * 2);
		StreamMapper<Integer, Integer> mul10Mapper = StreamMapper.create(input -> input * 10);

		await(supplier.transformWith(squareMapper).transformWith(doubleMapper)
				.streamTo(consumer.transformWith(mul10Mapper).transformWith(randomlySuspending())));

		assertEquals(asList(20, 80, 180, 320, 500, 720), consumer.getList());

		assertEndOfStream(supplier);
		assertEndOfStream(squareMapper);
		assertEndOfStream(doubleMapper);
		assertEndOfStream(mul10Mapper);
		assertEndOfStream(consumer);
	}
}
