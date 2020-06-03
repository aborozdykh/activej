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

package io.activej.dataflow.dataset;

import io.activej.dataflow.dataset.impl.*;
import io.activej.dataflow.graph.DataflowContext;
import io.activej.dataflow.graph.Partition;
import io.activej.dataflow.graph.StreamId;
import io.activej.datastream.processor.StreamJoin.Joiner;
import io.activej.datastream.processor.StreamReducers.Reducer;
import io.activej.datastream.processor.StreamReducers.ReducerToResult;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

public final class Datasets {

	public static <K, T> SortedDataset<K, T> castToSorted(Dataset<T> dataset, Class<K> keyType,
	                                                      Function<T, K> keyFunction, Comparator<K> keyComparator) {
		return new SortedDataset<K, T>(dataset.valueType(), keyComparator, keyType, keyFunction) {
			@Override
			public List<StreamId> channels(DataflowContext context) {
				return dataset.channels(context);
			}
		};
	}

	public static <K, T> SortedDataset<K, T> castToSorted(LocallySortedDataset<K, T> dataset) {
		return new SortedDataset<K, T>(dataset.valueType(), dataset.keyComparator(), dataset.keyType(),
				dataset.keyFunction()) {
			@Override
			public List<StreamId> channels(DataflowContext context) {
				return dataset.channels(context);
			}
		};
	}

	public static <K, L, R, V> SortedDataset<K, V> join(SortedDataset<K, L> left, SortedDataset<K, R> right,
	                                                    Joiner<K, L, R, V> joiner,
	                                                    Class<V> resultType, Function<V, K> keyFunction) {
		return new DatasetJoin<>(left, right, joiner, resultType, keyFunction);
	}

	public static <I, O> Dataset<O> map(Dataset<I> dataset, Function<I, O> mapper, Class<O> resultType) {
		return new DatasetMap<>(dataset, mapper, resultType);
	}

	public static <T> Dataset<T> map(Dataset<T> dataset, Function<T, T> mapper) {
		return map(dataset, mapper, dataset.valueType());
	}

	public static <T> Dataset<T> filter(Dataset<T> dataset, Predicate<T> predicate) {
		return new DatasetFilter<>(dataset, predicate, dataset.valueType());
	}

	public static <K, I> LocallySortedDataset<K, I> localSort(Dataset<I> dataset, Class<K> keyType,
	                                                          Function<I, K> keyFunction, Comparator<K> keyComparator) {
		return new DatasetLocalSort<>(dataset, keyType, keyFunction, keyComparator);
	}

	public static <K, I, O> LocallySortedDataset<K, O> localReduce(LocallySortedDataset<K, I> stream,
	                                                               Reducer<K, I, O, ?> reducer,
	                                                               Class<O> resultType,
	                                                               Function<O, K> resultKeyFunction) {
		return new DatasetLocalSortReduce<>(stream, reducer, resultType, resultKeyFunction);
	}

	public static <K, I, O> Dataset<O> repartitionReduce(LocallySortedDataset<K, I> dataset,
	                                                      Reducer<K, I, O, ?> reducer,
	                                                      Class<O> resultType) {
		return new DatasetRepartitionReduce<>(dataset, reducer, resultType);
	}

	public static <K, I, O> Dataset<O> repartitionReduce(LocallySortedDataset<K, I> dataset,
	                                                      Reducer<K, I, O, ?> reducer,
	                                                      Class<O> resultType, List<Partition> partitions) {
		return new DatasetRepartitionReduce<>(dataset, reducer, resultType, partitions);
	}

	public static <K, T> SortedDataset<K, T> repartitionSort(LocallySortedDataset<K, T> dataset) {
		return new DatasetRepartitionAndSort<>(dataset);
	}

	public static <K, T> SortedDataset<K, T> repartitionSort(LocallySortedDataset<K, T> dataset,
	                                                          List<Partition> partitions) {
		return new DatasetRepartitionAndSort<>(dataset, partitions);
	}

	public static <K, I, O, A> Dataset<O> sortReduceRepartitionReduce(Dataset<I> dataset,
	                                                                     ReducerToResult<K, I, O, A> reducer,
	                                                                     Class<K> keyType,
	                                                                     Function<I, K> inputKeyFunction,
	                                                                     Comparator<K> keyComparator,
	                                                                     Class<A> accumulatorType,
	                                                                     Function<A, K> accumulatorKeyFunction,
	                                                                     Class<O> outputType) {
		LocallySortedDataset<K, I> partiallySorted = localSort(dataset, keyType, inputKeyFunction, keyComparator);
		LocallySortedDataset<K, A> partiallyReduced = localReduce(partiallySorted, reducer.inputToAccumulator(),
				accumulatorType, accumulatorKeyFunction);
		return repartitionReduce(partiallyReduced, reducer.accumulatorToOutput(), outputType);
	}

	public static <K, I, A> Dataset<A> sortReduceRepartitionReduce(Dataset<I> dataset,
	                                                                  ReducerToResult<K, I, A, A> reducer,
	                                                                  Class<K> keyType,
	                                                                  Function<I, K> inputKeyFunction,
	                                                                  Comparator<K> keyComparator,
	                                                                  Class<A> accumulatorType,
	                                                                  Function<A, K> accumulatorKeyFunction) {
		return sortReduceRepartitionReduce(dataset, reducer,
				keyType, inputKeyFunction, keyComparator,
				accumulatorType, accumulatorKeyFunction, accumulatorType
		);
	}

	public static <K, T> Dataset<T> sortReduceRepartitionReduce(Dataset<T> dataset,
	                                                               ReducerToResult<K, T, T, T> reducer,
	                                                               Class<K> keyType, Function<T, K> keyFunction,
	                                                               Comparator<K> keyComparator) {
		return sortReduceRepartitionReduce(dataset, reducer,
				keyType, keyFunction, keyComparator,
				dataset.valueType(), keyFunction, dataset.valueType()
		);
	}

	public static <K, I, O, A> Dataset<O> splitSortReduceRepartitionReduce(Dataset<I> dataset,
	                                                                         ReducerToResult<K, I, O, A> reducer,
	                                                                         Function<I, K> inputKeyFunction,
	                                                                         Comparator<K> keyComparator,
	                                                                         Class<A> accumulatorType,
	                                                                         Function<A, K> accumulatorKeyFunction,
	                                                                         Class<O> outputType) {
		return new DatasetSplitSortReduceRepartitionReduce<>(dataset, inputKeyFunction, accumulatorKeyFunction, keyComparator,
				reducer, outputType, accumulatorType);

	}

	public static <K, I, A> Dataset<A> splitSortReduceRepartitionReduce(Dataset<I> dataset,
	                                                                      ReducerToResult<K, I, A, A> reducer,
	                                                                      Function<I, K> inputKeyFunction,
	                                                                      Comparator<K> keyComparator,
	                                                                      Class<A> accumulatorType,
	                                                                      Function<A, K> accumulatorKeyFunction) {
		return splitSortReduceRepartitionReduce(dataset, reducer,
				inputKeyFunction, keyComparator,
				accumulatorType, accumulatorKeyFunction, accumulatorType
		);
	}

	public static <K, T> Dataset<T> splitSortReduceRepartitionReduce(Dataset<T> dataset,
			ReducerToResult<K, T, T, T> reducer,
			Function<T, K> keyFunction,
			Comparator<K> keyComparator) {
		return splitSortReduceRepartitionReduce(dataset, reducer,
				keyFunction, keyComparator,
				dataset.valueType(), keyFunction, dataset.valueType()
		);
	}

	public static <T> Dataset<T> datasetOfList(String dataId, Class<T> resultType) {
		return new DatasetListSupplier<>(dataId, resultType);
	}

	public static <K, T> SortedDataset<K, T> sortedDatasetOfList(String dataId, Class<T> resultType, Class<K> keyType,
			Function<T, K> keyFunction, Comparator<K> keyComparator) {
		return castToSorted(datasetOfList(dataId, resultType), keyType, keyFunction, keyComparator);
	}

	public static <T> DatasetListConsumer<T> listConsumer(Dataset<T> input, String listId) {
		return new DatasetListConsumer<>(input, listId);
	}
}
