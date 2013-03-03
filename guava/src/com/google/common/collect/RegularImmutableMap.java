/*
* Copyright (C) 2008 The Guava Authors
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

package com.google.common.collect;

import com.google.common.annotations.GwtCompatible;
import com.google.common.collect.ImmutableMapEntrySet.ArrayEntrySet;

import javax.annotation.Nullable;

/**
 * Implementation of {@link ImmutableMap} with two or more entries.
 *
 * @author Jesse Wilson
 * @author Kevin Bourrillion
 * @author Gregory Kick
 */
@GwtCompatible(serializable = true, emulated = true)
final class RegularImmutableMap<K, V> extends ImmutableMap<K, V> {

  // entries in insertion order
  private final transient ImmutableMapEntry<K, V>[] entries;
  // array of linked lists of entries
  private final transient ImmutableMapEntry<K, V>[] table;
  // 'and' with an int to get a table index
  private final transient int mask;
  
  RegularImmutableMap(TerminalMapEntry<?, ?>... theEntries) {
    this(theEntries.length, theEntries);
  }
  
  /**
   * Constructor for RegularImmutableMap.  Assumes all elements from {@code theEntries}
   * are {@code ImmutableMapEntry} instances and not {@code NonTerminalMapEntry} instances,
   * although it is not feasible to force this type on the array.
   * 
   * <p>This allows reuse of the entry objects from the array in the actual implementation.
   */
  RegularImmutableMap(int size, TerminalMapEntry<?, ?>[] theEntries) {
    entries = createEntryArray(size);
    int tableSize = Hashing.closedTableSize(size, MAX_LOAD_FACTOR);
    table = createEntryArray(tableSize);
    mask = tableSize - 1;
    for (int entryIndex = 0; entryIndex < size; entryIndex++) {
      @SuppressWarnings("unchecked")
      TerminalMapEntry<K, V> entry = (TerminalMapEntry<K, V>) theEntries[entryIndex];
      K key = entry.getKey();
      int tableIndex = Hashing.smear(key.hashCode()) & mask;
      @Nullable ImmutableMapEntry<K, V> existing = table[tableIndex];
      // prepend, not append, so the entries can be immutable
      ImmutableMapEntry<K, V> newEntry = (existing == null)
          ? entry
          : new NonTerminalMapEntry<K, V>(entry, existing);
      table[tableIndex] = newEntry;
      entries[entryIndex] = newEntry;
      checkNoConflictInBucket(key, newEntry, existing);
    }
  }
  
  /**
   * Constructor for RegularImmutableMap that makes no assumptions about the input entries.
   */
  RegularImmutableMap(Entry<?, ?>[] theEntries) {
    int size = theEntries.length;
    entries = createEntryArray(size);
    int tableSize = Hashing.closedTableSize(size, MAX_LOAD_FACTOR);
    table = createEntryArray(tableSize);
    mask = tableSize - 1;
    for (int entryIndex = 0; entryIndex < size; entryIndex++) {
      @SuppressWarnings("unchecked") // all our callers carefully put in only Entry<K, V>s
      Entry<K, V> entry = (Entry<K, V>) theEntries[entryIndex];
      K key = entry.getKey();
      V value = entry.getValue();
      int tableIndex = Hashing.smear(key.hashCode()) & mask;
      @Nullable ImmutableMapEntry<K, V> existing = table[tableIndex];
      // prepend, not append, so the entries can be immutable
      ImmutableMapEntry<K, V> newEntry = (existing == null)
          ? new TerminalMapEntry<K, V>(key, value)
          : new NonTerminalMapEntry<K, V>(key, value, existing);
      table[tableIndex] = newEntry;
      entries[entryIndex] = newEntry;
      checkNoConflictInBucket(key, newEntry, existing);
    }
  }

  private void checkNoConflictInBucket(
      K key, ImmutableMapEntry<K, V> entry, ImmutableMapEntry<K, V> bucketHead) {
    for (; bucketHead != null; bucketHead = bucketHead.getNextInBucket()) {
      checkNoConflict(!key.equals(bucketHead.getKey()), "key", entry, bucketHead);
    }
  }
  
  private static abstract class ImmutableMapEntry<K, V> extends ImmutableEntry<K, V> {
    ImmutableMapEntry(K key, V value) {
      super(key, value);
      checkEntryNotNull(key, value);
    }
    
    ImmutableMapEntry(ImmutableMapEntry<K, V> entry) {
      super(entry.getKey(), entry.getValue());
      // omit the null check
    }
    
    @Nullable
    abstract ImmutableMapEntry<K, V> getNextInBucket();
  }
  
  static final class TerminalMapEntry<K, V> extends ImmutableMapEntry<K, V> {
    private TerminalMapEntry(ImmutableMapEntry<K, V> entry) {
      super(entry);
    }

    TerminalMapEntry(K key, V value) {
      super(key, value);
    }

    @Override
    @Nullable
    ImmutableMapEntry<K, V> getNextInBucket() {
      return null;
    }
  }
  
  private static final class NonTerminalMapEntry<K, V> extends ImmutableMapEntry<K, V> {
    private final ImmutableMapEntry<K, V> nextInBucket;

    NonTerminalMapEntry(K key, V value,
        ImmutableMapEntry<K, V> nextInBucket) {
      super(key, value);
      this.nextInBucket = nextInBucket;
    }
    
    // overload omitting the null check
    NonTerminalMapEntry(ImmutableMapEntry<K, V> contents, ImmutableMapEntry<K, V> nextInBucket) {
      super(contents);
      this.nextInBucket = nextInBucket;
    }

    @Override
    ImmutableMapEntry<K, V> getNextInBucket() {
      return nextInBucket;
    }
  }

  /**
   * Closed addressing tends to perform well even with high load factors.
   * Being conservative here ensures that the table is still likely to be
   * relatively sparse (hence it misses fast) while saving space.
   */
  private static final double MAX_LOAD_FACTOR = 1.2;

  /**
   * Creates an {@code ImmutableMapEntry} array to hold parameterized entries. The
   * result must never be upcast back to ImmutableMapEntry[] (or Object[], etc.), or
   * allowed to escape the class.
   */
  @SuppressWarnings("unchecked") // Safe as long as the javadocs are followed
  private ImmutableMapEntry<K, V>[] createEntryArray(int size) {
    return new ImmutableMapEntry[size];
  }

  @Override public V get(@Nullable Object key) {
    if (key == null) {
      return null;
    }
    int index = Hashing.smear(key.hashCode()) & mask;
    for (ImmutableMapEntry<K, V> entry = table[index];
        entry != null;
        entry = entry.getNextInBucket()) {
      K candidateKey = entry.getKey();

      /*
       * Assume that equals uses the == optimization when appropriate, and that
       * it would check hash codes as an optimization when appropriate. If we
       * did these things, it would just make things worse for the most
       * performance-conscious users.
       */
      if (key.equals(candidateKey)) {
        return entry.getValue();
      }
    }
    return null;
  }

  @Override
  public int size() {
    return entries.length;
  }
  
  @Override boolean isPartialView() {
    return false;
  }

  @Override
  ImmutableSet<Entry<K, V>> createEntrySet() {
    return new ArrayEntrySet<K, V>(this, entries);
  }

  // This class is never actually serialized directly, but we have to make the
  // warning go away (and suppressing would suppress for all nested classes too)
  private static final long serialVersionUID = 0;
}
