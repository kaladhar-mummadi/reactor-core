/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Fuseable;
import reactor.core.Scannable;

/**
 * Reduce the sequence of values in each 'rail' to a single value.
 *
 * @param <T> the input value type
 * @param <R> the result value type
 */
final class ParallelReduceSeed<T, R> extends ParallelFlux<R> implements
                                                             Scannable, Fuseable {

	final ParallelFlux<? extends T> source;

	final Supplier<R> initialSupplier;

	final BiFunction<R, ? super T, R> reducer;

	ParallelReduceSeed(ParallelFlux<? extends T> source,
			Supplier<R> initialSupplier,
			BiFunction<R, ? super T, R> reducer) {
		this.source = source;
		this.initialSupplier = initialSupplier;
		this.reducer = reducer;
	}

	@Override
	public Object scan(Scannable.Attr key) {
		switch (key){
			case PARENT:
				return source;
			case PREFETCH:
				return getPrefetch();
		}
		return null;
	}

	@Override
	public int getPrefetch() {
		return Integer.MAX_VALUE;
	}

	@Override
	public void subscribe(Subscriber<? super R>[] subscribers) {
		if (!validate(subscribers)) {
			return;
		}

		int n = subscribers.length;
		@SuppressWarnings("unchecked") Subscriber<T>[] parents = new Subscriber[n];

		for (int i = 0; i < n; i++) {

			R initialValue;

			try {
				initialValue = Objects.requireNonNull(initialSupplier.get(),
						"The initialSupplier returned a null value");
			}
			catch (Throwable ex) {
				reportError(subscribers, Operators.onOperatorError(ex));
				return;
			}
			parents[i] =
					new ParallelReduceSeedSubscriber<>(subscribers[i], initialValue, reducer);
		}

		source.subscribe(parents);
	}

	void reportError(Subscriber<?>[] subscribers, Throwable ex) {
		for (Subscriber<?> s : subscribers) {
			Operators.error(s, ex);
		}
	}

	@Override
	public int parallelism() {
		return source.parallelism();
	}


	static final class ParallelReduceSeedSubscriber<T, R>
			extends Operators.MonoSubscriber<T, R> {

		final BiFunction<R, ? super T, R> reducer;

		R accumulator;

		Subscription s;

		boolean done;

		ParallelReduceSeedSubscriber(Subscriber<? super R> subscriber,
				R initialValue,
				BiFunction<R, ? super T, R> reducer) {
			super(subscriber);
			this.accumulator = initialValue;
			this.reducer = reducer;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;

				actual.onSubscribe(this);

				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(T t) {
			if (done) {
				Operators.onNextDropped(t);
				return;
			}

			R v;

			try {
				v = Objects.requireNonNull(reducer.apply(accumulator, t), "The reducer returned a null value");
			}
			catch (Throwable ex) {
				onError(Operators.onOperatorError(this, ex, t));
				return;
			}

			accumulator = v;
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Operators.onErrorDropped(t);
				return;
			}
			done = true;
			accumulator = null;
			actual.onError(t);
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;

			R a = accumulator;
			accumulator = null;
			complete(a);
		}

		@Override
		public void cancel() {
			super.cancel();
			s.cancel();
		}
	}
}
