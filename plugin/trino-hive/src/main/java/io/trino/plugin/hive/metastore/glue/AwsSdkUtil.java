/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.hive.metastore.glue;

import com.google.common.collect.AbstractIterator;
import io.trino.plugin.hive.aws.AwsApiCallStats;
import io.trino.spi.TrinoException;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.services.glue.model.GlueException;

import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.google.common.collect.Streams.stream;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_METASTORE_ERROR;
import static java.util.Objects.requireNonNull;

public final class AwsSdkUtil
{
    private AwsSdkUtil() {}

    public static <Request, Result> Stream<Result> getPaginatedResultsForS3(
            Function<Request, Result> submission,
            Request request,
            BiConsumer<Request, String> setNextToken,
            Function<Result, String> extractNextToken,
            AwsApiCallStats stats)
    {
        requireNonNull(submission, "submission is null");
        requireNonNull(request, "request is null");
        requireNonNull(setNextToken, "setNextToken is null");
        requireNonNull(extractNextToken, "extractNextToken is null");

        Iterator<Result> iterator = new AbstractIterator<>()
        {
            private String nextToken;
            private boolean firstRequest = true;

            @Override
            protected Result computeNext()
            {
                if (nextToken == null && !firstRequest) {
                    return endOfData();
                }

                setNextToken.accept(request, nextToken);
                Result result = stats.call(() -> submission.apply(request));
                firstRequest = false;
                nextToken = extractNextToken.apply(result);
                return result;
            }
        };

        return stream(iterator);
    }

    /**
     * Helper method to handle sync request with async client
     */
    public static <Request, Result> Result awsSyncRequest(
            Function<Request, CompletableFuture<Result>> submission,
            Request request,
            AwsApiCallStats stats)
    {
        requireNonNull(submission, "submission is null");
        requireNonNull(request, "request is null");
        try {
            if (stats != null) {
                return stats.call(() -> submission.apply(request).join());
            }

            return submission.apply(request).join();
        }
        catch (CompletionException e) {
            if (e.getCause() instanceof GlueException glueException) {
                throw glueException;
            }
            throw new TrinoException(HIVE_METASTORE_ERROR, e.getCause());
        }
    }

    /**
     * Helper method to handle sync paginated request with async client
     */
    public static <Result> void awsSyncPaginatedRequest(SdkPublisher<Result> paginator, Consumer<Result> resultConsumer, AwsApiCallStats stats)
    {
        requireNonNull(paginator, "paginator is null");
        requireNonNull(resultConsumer, "resultConsumer is null");
        try {
            CompletableFuture<Void> paginationFuture;
            if (stats != null) {
                paginationFuture = stats.call(() -> paginator.subscribe(resultConsumer));
            }
            else {
                paginationFuture = paginator.subscribe(resultConsumer);
            }

            // for paginator.subscribe() to complete
            paginationFuture.join();
        }
        catch (CompletionException e) {
            if (e.getCause() instanceof GlueException glueException) {
                throw glueException;
            }
            throw new TrinoException(HIVE_METASTORE_ERROR, e.getCause());
        }
    }
}
