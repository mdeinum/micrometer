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
package io.micrometer.core.instrument;

import org.junit.jupiter.api.Test;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class MeterIdTest {
    @Test
    void withStatistic() {
        Meter.Id id = new Meter.Id("my.id", emptyList(), null, null);
        assertThat(id.withTag(Statistic.TotalTime).getTags()).contains(Tag.of("statistic", "totalTime"));
    }

    @Test
    void equalsAndHashCode() {
        Meter.Id id = new Meter.Id("my.id", emptyList(), null, null);
        Meter.Id id2 = new Meter.Id("my.id", emptyList(), null, null);

        assertThat(id).isEqualTo(id2);
        assertThat(id.hashCode()).isEqualTo(id2.hashCode());
    }
}