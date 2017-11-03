/**
 * Copyright 2017 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.spring.web.servlet;

import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author Jon Schneider
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "security.ignored=/**")
public class MetricsFilterTest {

    private static final CountDownLatch longRequestCountDown = new CountDownLatch(1);

    @Autowired
    private PrometheusMeterRegistry registry;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MetricsFilter filter;

    private MockMvc mvc;

    @Before
    public void setupMockMvc() {
        this.mvc = MockMvcBuilders
            .webAppContextSetup(this.context)
            .addFilters(filter)
            .build();
    }

    @Test
    public void timedMethod() throws Exception {
        this.mvc.perform(get("/api/c1/10")).andExpect(status().isOk());

        assertThat(this.registry.find("http.server.requests")
            .tags("status", "200", "uri", "/api/c1/{id}", "public", "true")
            .value(Statistic.Count, 1.0).timer()).isPresent();
    }

    @Test
    public void subclassedTimedMethod() throws Exception {
        this.mvc.perform(get("/api/c1/metaTimed/10")).andExpect(status().isOk());

        assertThat(this.registry.find("http.server.requests")
            .tags("status", "200", "uri", "/api/c1/metaTimed/{id}")
            .value(Statistic.Count, 1.0).timer()).isPresent();
    }

    @Test
    public void untimedMethod() throws Exception {
        this.mvc.perform(get("/api/c1/untimed/10")).andExpect(status().isOk());

        assertThat(this.registry.find("http.server.requests")
            .tags("uri", "/api/c1/untimed/10").timer()).isEmpty();
    }

    @Test
    public void timedControllerClass() throws Exception {
        this.mvc.perform(get("/api/c2/10")).andExpect(status().isOk());

        assertThat(this.registry.find("http.server.requests").tags("status", "200")
            .value(Statistic.Count, 1.0)
            .timer()).isPresent();
    }

    @Test
    public void badClientRequest() throws Exception {
        this.mvc.perform(get("/api/c1/oops")).andExpect(status().is4xxClientError());

        assertThat(this.registry.find("http.server.requests").tags("status", "400")
            .value(Statistic.Count, 1.0)
            .timer()).isPresent();
    }

    @Test
    public void unhandledError() throws Exception {
        assertThatCode(() -> this.mvc.perform(get("/api/c1/unhandledError/10"))
            .andExpect(status().isOk())
            .andDo(print()))
            .hasRootCauseInstanceOf(RuntimeException.class);

        assertThat(this.registry.find("http.server.requests")
            .tags("exception", "NestedServletException").value(Statistic.Count, 1.0)
            .timer()).isPresent();
    }

    @Test
    public void longRunningRequest() throws Exception {
        MvcResult result = this.mvc.perform(get("/api/c1/long/10"))
            .andExpect(request().asyncStarted()).andReturn();

        // while the mapping is running, it contributes to the activeTasks count
        assertThat(this.registry.find("my.long.request").tags("region", "test")
            .value(Statistic.Count, 1.0).longTaskTimer()).isPresent();

        // once the mapping completes, we can gather information about status, etc.
        longRequestCountDown.countDown();

        this.mvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        assertThat(this.registry.find("http.server.requests").tags("status", "200")
            .value(Statistic.Count, 1.0).timer()).isPresent();
    }

    @Test
    public void endpointThrowsError() throws Exception {
        this.mvc.perform(get("/api/c1/error/10")).andExpect(status().is4xxClientError());

        assertThat(this.registry.find("http.server.requests").tags("status", "422")
            .value(Statistic.Count, 1.0).timer()).isPresent();
    }

    @Test
    public void regexBasedRequestMapping() throws Exception {
        this.mvc.perform(get("/api/c1/regex/.abc")).andExpect(status().isOk());

        assertThat(this.registry.find("http.server.requests")
            .tags("uri", "/api/c1/regex/{id:\\.[a-z]+}").value(Statistic.Count, 1.0)
            .timer()).isPresent();
    }

    @Test
    public void recordQuantiles() throws Exception {
        this.mvc.perform(get("/api/c1/percentiles/10")).andExpect(status().isOk());

        assertThat(this.registry.scrape()).contains("quantile=\"0.5\"");
        assertThat(this.registry.scrape()).contains("quantile=\"0.95\"");
    }

    @Test
    public void recordHistogram() throws Exception {
        this.mvc.perform(get("/api/c1/histogram/10")).andExpect(status().isOk());

        assertThat(this.registry.scrape()).contains("le=\"0.001\"");
        assertThat(this.registry.scrape()).contains("le=\"30.0\"");
    }

    @Target({ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Timed(percentiles = 0.95)
    public @interface Timed95 {
    }

    @SpringBootApplication(scanBasePackages = "ignored")
    @Import({Controller1.class, Controller2.class})
    static class MetricsFilterApp {
        @Bean
        MeterRegistry meterRegistry() {
            // one of the few registries that support aggregable percentiles
            return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        }
    }

    @RestController
    @RequestMapping("/api/c1")
    static class Controller1 {

        @Timed(extraTags = {"public", "true"})
        @GetMapping("/{id}")
        public String successfulWithExtraTags(@PathVariable Long id) {
            return id.toString();
        }

        @Timed
        @Timed(value = "my.long.request", extraTags = {"region", "test"}, longTask = true)
        @GetMapping("/long/{id}")
        public Callable<String> takesLongTimeToSatisfy(@PathVariable Long id) {
            return () -> {
                try {
                    longRequestCountDown.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return id.toString();
            };
        }

        @GetMapping("/untimed/{id}")
        public String successfulButUntimed(@PathVariable Long id) {
            return id.toString();
        }

        @Timed
        @GetMapping("/error/{id}")
        public String alwaysThrowsException(@PathVariable Long id) {
            throw new IllegalStateException("Boom on "+id+"!");
        }

        @Timed
        @GetMapping("/unhandledError/{id}")
        public String alwaysThrowsUnhandledException(@PathVariable Long id) {
            throw new RuntimeException("Boom on "+id+"!");
        }

        @Timed
        @GetMapping("/regex/{id:\\.[a-z]+}")
        public String successfulRegex(@PathVariable String id) {
            return id;
        }

        @Timed(percentiles = {0.50, 0.95})
        @GetMapping("/percentiles/{id}")
        public String percentiles(@PathVariable String id) {
            return id;
        }

        @Timed(histogram = true)
        @GetMapping("/histogram/{id}")
        public String histogram(@PathVariable String id) {
            return id;
        }

        @Timed95
        @GetMapping("/metaTimed/{id}")
        public String meta(@PathVariable String id) {
            return id;
        }

        @ExceptionHandler(IllegalStateException.class)
        @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
        ModelAndView defaultErrorHandler(HttpServletRequest request, Exception e) {
            return new ModelAndView("myerror");
        }
    }

    @RestController
    @Timed
    @RequestMapping("/api/c2")
    static class Controller2 {
        @GetMapping("/{id}")
        public String successful(@PathVariable Long id) {
            return id.toString();
        }
    }
}
