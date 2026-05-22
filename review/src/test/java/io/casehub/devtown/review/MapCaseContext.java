/*
 * Copyright 2026-Present The Case Hub Authors
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
package io.casehub.devtown.review;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.context.CaseContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Test-only CaseContext backed by a flat Map&lt;String, Object&gt;.
 * Supports get(), contains(), and getPath() for nested access.
 * All other methods throw UnsupportedOperationException.
 */
class MapCaseContext implements CaseContext {

  private final Map<String, Object> data;

  MapCaseContext(Map<String, Object> data) {
    this.data = data;
  }

  @Override
  public Map<String, Object> getData() {
    return data;
  }

  @Override
  public Object get(String key) {
    return data.get(key);
  }

  @Override
  public boolean contains(String key) {
    return data.containsKey(key);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Object getPath(String path) {
    String[] parts = path.split("\\.", 2);
    Object val = data.get(parts[0]);
    if (parts.length == 1 || val == null) {
      return val;
    }
    if (val instanceof Map<?, ?> m) {
      return new MapCaseContext((Map<String, Object>) m).getPath(parts[1]);
    }
    return null;
  }

  // ── Stubs ──────────────────────────────────────────────────────────

  @Override
  public CaseContext set(String key, Object value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T getAs(String key, Class<T> type) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T getOrDefault(String key, T defaultValue) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Object computeIfAbsent(String key, Function<String, Object> mappingFunction) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Object putIfAbsent(String key, Object value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean compareAndSet(String key, Object expected, Object newValue) {
    throw new UnsupportedOperationException();
  }

  @Override
  public CaseContext update(String key, Function<Object, Object> updateFunction) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getString(String key) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Integer getInt(String key) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Long getLong(String key) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Double getDouble(String key) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Boolean getBoolean(String key) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> List<T> getList(String key, Class<T> elementType) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getPathAsString(String path) {
    throw new UnsupportedOperationException();
  }

  @Override
  public CaseContext setPath(String path, Object value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<JsonNode> applyAndDiff(String path, Object value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public CaseContext setAll(Map<String, Object> values) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Map<String, Object> getAll(String... keys) {
    throw new UnsupportedOperationException();
  }

  @Override
  public CaseContext remove(String key) {
    throw new UnsupportedOperationException();
  }

  @Override
  public CaseContext clear() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<String> getKeys() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isEmpty() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int size() {
    throw new UnsupportedOperationException();
  }

  @Override
  public JsonNode asJsonNode() {
    throw new UnsupportedOperationException();
  }

  @Override
  public CaseContext merge(CaseContext other) {
    throw new UnsupportedOperationException();
  }

  @Override
  public CaseContext snapshot() {
    throw new UnsupportedOperationException();
  }

  @Override
  public JsonNode diff(CaseContext other) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void applyDiff(JsonNode diff) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getVersion() {
    throw new UnsupportedOperationException();
  }

}
