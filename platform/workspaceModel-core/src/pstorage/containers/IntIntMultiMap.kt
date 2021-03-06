// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.workspace.api.pstorage.containers

import gnu.trove.TIntIntHashMap
import org.jetbrains.annotations.TestOnly

/**
 * @author Alex Plate
 *
 * See:
 *  - [IntIntMultiMap.ByList]
 *  - [IntIntMultiMap.BySet]
 * and
 *  - [MutableIntIntMultiMap.ByList]
 *  - [MutableIntIntMultiMap.BySet]
 */

internal sealed class IntIntMultiMap(
  values: IntArray,
  links: TIntIntHashMap,
  distinctValues: Boolean
) : AbstractIntIntMultiMap(values, links, distinctValues) {

  class BySet internal constructor(values: IntArray, links: TIntIntHashMap) : IntIntMultiMap(values, links, true) {
    constructor() : this(IntArray(0), TIntIntHashMap())

    override fun copy(): BySet = doCopy().let { BySet(it.first, it.second) }

    override fun toMutable(): MutableIntIntMultiMap.BySet = MutableIntIntMultiMap.BySet(values.clone(), links.clone() as TIntIntHashMap)
  }

  class ByList internal constructor(values: IntArray, links: TIntIntHashMap) : IntIntMultiMap(values, links, false) {
    constructor() : this(IntArray(0), TIntIntHashMap())

    override fun copy(): ByList = doCopy().let { ByList(it.first, it.second) }

    override fun toMutable(): MutableIntIntMultiMap.ByList = MutableIntIntMultiMap.ByList(values.clone(), links.clone() as TIntIntHashMap)
  }

  override operator fun get(key: Int): IntSequence {
    if (key !in links) return EmptyIntSequence
    val idx = links[key]
    if (idx >= 0) return SingleResultIntSequence(idx)
    return RoMultiResultIntSequence(values, idx.unpack())
  }

  abstract fun toMutable(): MutableIntIntMultiMap

  private class RoMultiResultIntSequence(
    private val values: IntArray,
    private val idx: Int
  ) : IntSequence() {

    override fun getIterator(): IntIterator = object : IntIterator() {
      private var index = idx
      private var hasNext = true

      override fun hasNext(): Boolean = hasNext

      override fun nextInt(): Int {
        val value = values[index++]
        return if (value < 0) {
          hasNext = false
          value.unpack()
        }
        else {
          value
        }
      }
    }
  }
}

internal sealed class MutableIntIntMultiMap(
  values: IntArray,
  links: TIntIntHashMap,
  distinctValues: Boolean
) : AbstractIntIntMultiMap(values, links, distinctValues) {

  class BySet internal constructor(values: IntArray, links: TIntIntHashMap) : MutableIntIntMultiMap(values, links, true) {
    constructor() : this(IntArray(0), TIntIntHashMap())

    override fun copy(): BySet = doCopy().let { BySet(it.first, it.second) }

    override fun toImmutable(): IntIntMultiMap.BySet {
      return IntIntMultiMap.BySet(values.clone(), links.clone() as TIntIntHashMap)
    }
  }

  class ByList internal constructor(values: IntArray, links: TIntIntHashMap) : MutableIntIntMultiMap(values, links, false) {
    constructor() : this(IntArray(0), TIntIntHashMap())

    override fun toImmutable(): IntIntMultiMap.ByList {
      return IntIntMultiMap.ByList(values.clone(), links.clone() as TIntIntHashMap)
    }

    override fun copy(): ByList = doCopy().let { ByList(it.first, it.second) }
  }

  override fun get(key: Int): IntSequence {
    if (key !in links) return EmptyIntSequence

    var idx = links[key]
    if (idx >= 0) return SingleResultIntSequence(idx)

    // idx is a link to  values
    idx = idx.unpack()
    val size = size(key)
    val vals = values.sliceArray(idx until (idx + size))
    vals[vals.lastIndex] = vals.last().unpack()
    return RwIntSequence(vals)

  }

  fun put(key: Int, value: Int) {
    putAll(key, intArrayOf(value))
  }

  private fun exists(value: Int, startRange: Int, endRange: Int): Boolean {
    for (i in startRange until endRange) {
      if (values[i] == value) return true
    }
    if (values[endRange] == value.pack()) return true
    return false
  }

  fun putAll(key: Int, newValues: IntArray): Boolean {
    if (newValues.isEmpty()) return false
    return if (key in links) {
      var idx = links[key]
      if (idx < 0) {
        // Adding new values to existing that are already stored in the [values] array
        idx = idx.unpack()
        val endIndexInclusive = idx + size(key)

        val filteredValues = if (distinctValues) {
          newValues.filterNot { exists(it, idx, endIndexInclusive - 1) }.toTypedArray().toIntArray()
        }
        else newValues

        val newValuesSize = filteredValues.size

        val newArray = IntArray(values.size + newValuesSize)
        values.copyInto(newArray, 0, 0, endIndexInclusive)
        if (endIndexInclusive + newValuesSize < newArray.size) {
          values.copyInto(newArray, endIndexInclusive + newValuesSize, endIndexInclusive)
        }
        filteredValues.forEachIndexed { index, value ->
          newArray[endIndexInclusive + index] = value
        }
        newArray[endIndexInclusive + newValuesSize - 1] = filteredValues.last().pack()
        val oldPrevValue = newArray[endIndexInclusive - 1]
        newArray[endIndexInclusive - 1] = oldPrevValue.unpack()
        this.values = newArray

        // Update existing links
        rightShiftLinks(idx, newValuesSize)

        true // Returned value
      }
      else {
        // This map already contains value, but it's stored directly in the [links]
        // We should take this value, prepend to the new values and store them into [values]
        val newValuesSize = newValues.size
        val arraySize = values.size
        val newArray = IntArray(arraySize + newValuesSize + 1) // plus one for the value from links

        values.copyInto(newArray) // Put all previous values into array
        newArray[arraySize] = idx // Put an existing value into array
        newValues.copyInto(newArray, arraySize + 1)  // Put all new values
        newArray[arraySize + newValuesSize] = newValues.last().pack()  // Mark last value as the last one

        this.values = newArray

        links.put(key, arraySize.pack())

        true // Returned value
      }
    }
    else {
      // This key wasn't stored in the store before
      val newValuesSize = newValues.size
      if (newValuesSize > 1) {
        // There is more than one element in new values, so we should store them in [values]
        val arraySize = values.size
        val newArray = IntArray(arraySize + newValuesSize)

        values.copyInto(newArray)
        newValues.copyInto(newArray, arraySize)  // Put all new values

        newArray[arraySize + newValuesSize - 1] = newValues.last().pack()
        this.values = newArray

        links.put(key, arraySize.pack())

        true // Returned value
      }
      else {
        // Great! Only one value to store. No need to allocate memory in the [values]
        links.put(key, newValues.single())

        true // Returned value
      }
    }
  }

  fun remove(key: Int) {
    if (key !in links) return

    var idx = links[key]

    if (idx >= 0) {
      // Only one value in the store
      links.remove(key)
      return
    }

    idx = idx.unpack()

    val size = values.size

    val sizeToRemove = size(key)

    val newArray = IntArray(size - sizeToRemove)
    values.copyInto(newArray, 0, 0, idx)
    values.copyInto(newArray, idx, idx + sizeToRemove)
    values = newArray

    links.remove(key)

    // Update existing links
    rightShiftLinks(idx, -sizeToRemove)
  }

  fun remove(key: Int, value: Int): Boolean {
    if (key !in links) return false

    var idx = links[key]

    if (idx >= 0) {
      if (value == idx) {
        links.remove(key)
        return true
      }
      else return false
    }

    idx = idx.unpack()

    val valuesStartIndex = idx
    val size = values.size
    var foundIndex = -1

    // Search for the value in the values list
    var removeLast = false
    var valueUnderIdx: Int
    do {
      valueUnderIdx = values[idx]

      if (valueUnderIdx < 0) {
        // Last value in the sequence
        if (valueUnderIdx.unpack() == value) {
          foundIndex = idx
          removeLast = true
        }
        break
      }

      if (valueUnderIdx == value) {
        foundIndex = idx
        break
      }
      idx++
    }
    while (true)

    // There is no such value by this key
    if (foundIndex == -1) return false

    // If there is only two values for the key remains, after removing one of them we should put the remaining value directly into [links]
    val remainsOneValueInContainer = removeLast && idx == valuesStartIndex + 1   // Removing last value of two values
                                     || idx == valuesStartIndex && values[idx + 1] < 0 // Removing first value of two values

    return if (!remainsOneValueInContainer) {
      val newArray = IntArray(size - 1)
      values.copyInto(newArray, 0, 0, foundIndex)
      values.copyInto(newArray, foundIndex, foundIndex + 1)
      values = newArray
      if (removeLast) {
        values[foundIndex - 1] = values[foundIndex - 1].pack()
      }

      rightShiftLinks(idx, -1)

      true
    }
    else {
      val remainedValue = if (removeLast) values[idx - 1] else values[idx + 1].unpack()
      val newArray = IntArray(size - 2)
      values.copyInto(newArray, 0, 0, valuesStartIndex)
      values.copyInto(newArray, valuesStartIndex, valuesStartIndex + 2)
      values = newArray

      links.put(key, remainedValue)

      rightShiftLinks(idx, -2)

      true
    }
  }

  private fun rightShiftLinks(idx: Int, shiftTo: Int) {
    links.keys().forEach { keyToUpdate ->
      val valueToUpdate = links[keyToUpdate]
      if (valueToUpdate >= 0) return@forEach
      val unpackedValue = valueToUpdate.unpack()
      if (unpackedValue > idx) links.put(keyToUpdate, (unpackedValue + shiftTo).pack())
    }
  }

  fun clear() {
    links.clear()
    values = IntArray(0)
  }

  abstract fun toImmutable(): IntIntMultiMap

  private class RwIntSequence(private val values: IntArray) : IntSequence() {
    override fun getIterator(): IntIterator = values.iterator()
  }
}

internal sealed class AbstractIntIntMultiMap(
  protected var values: IntArray,
  protected val links: TIntIntHashMap,
  protected val distinctValues: Boolean
) {

  abstract operator fun get(key: Int): IntSequence

  fun get(key: Int, action: (Int) -> Unit) {
    if (key !in links) return

    var idx = links[key]
    if (idx >= 0) {
      // It's value
      action(idx)
      return
    }

    // It's a link to values
    idx = idx.unpack()

    var value: Int
    do {
      value = values[idx++]
      if (value < 0) break
      action(value)
    }
    while (true)

    action(value.unpack())
  }

  /** This method works o(n) */
  fun size(key: Int): Int {
    if (key !in links) return 0

    var idx = links[key]
    if (idx >= 0) return 1

    idx = idx.unpack()

    // idx is a link to values
    var res = 0

    while (values[idx++] >= 0) res++

    return res + 1
  }

  operator fun contains(key: Int): Boolean = key in links

  fun isEmpty(): Boolean = links.isEmpty

  abstract fun copy(): AbstractIntIntMultiMap

  protected fun doCopy(): Pair<IntArray, TIntIntHashMap> {
    val newLinks = TIntIntHashMap().clone() as TIntIntHashMap
    val newValues = values.clone()
    return newValues to newLinks
  }

  companion object {
    internal fun Int.pack(): Int = if (this == 0) Int.MIN_VALUE else -this
    internal fun Int.unpack(): Int = if (this == Int.MIN_VALUE) 0 else -this
  }

  abstract class IntSequence {

    protected abstract fun getIterator(): IntIterator

    fun forEach(action: (Int) -> Unit) {
      val iterator = getIterator()
      while (iterator.hasNext()) action(iterator.nextInt())
    }

    fun isEmpty(): Boolean = !getIterator().hasNext()

    /**
     * Please use this method only for debugging purposes.
     * Some of implementations doesn't have any memory overhead when using [IntSequence].
     */
    @TestOnly
    internal fun toArray(): IntArray {
      val list = ArrayList<Int>()
      this.forEach { list.add(it) }
      return list.toTypedArray().toIntArray()
    }

    open fun <T> map(transformation: (Int) -> T): Sequence<T> {
      return Sequence {
        object : Iterator<T> {
          private val iterator = getIterator()

          override fun hasNext(): Boolean = iterator.hasNext()

          override fun next(): T = transformation(iterator.nextInt())
        }
      }
    }
  }

  protected class SingleResultIntSequence(private val value: Int) : IntSequence() {
    override fun getIterator(): IntIterator = object : IntIterator() {

      private var hasNext = true

      override fun hasNext(): Boolean = hasNext

      override fun nextInt(): Int {
        if (!hasNext) throw NoSuchElementException()
        hasNext = false
        return value
      }
    }
  }

  protected object EmptyIntSequence : IntSequence() {
    override fun getIterator(): IntIterator = IntArray(0).iterator()

    override fun <T> map(transformation: (Int) -> T): Sequence<T> = emptySequence()
  }
}
