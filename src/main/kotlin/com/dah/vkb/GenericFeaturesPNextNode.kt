package com.dah.vkb

import org.lwjgl.system.MemoryStack.stackGet
import org.lwjgl.system.MemoryUtil.*
import org.lwjgl.system.NativeResource
import org.lwjgl.system.Struct
import org.lwjgl.system.StructBuffer
import java.lang.Integer.min
import java.nio.ByteBuffer

class GenericFeaturesPNextNode(container: ByteBuffer) :
    Struct(memAddress(container), __checkContainer(container, SIZEOF)), NativeResource {
    companion object {
        const val FIELD_CAPACITY = 256

        private val LAYOUT = __struct(
            __member(4),
            __member(POINTER_SIZE),
            __array(4, FIELD_CAPACITY)
        )

        val STYPE = LAYOUT.offsetof(0)
        val PNEXT = LAYOUT.offsetof(1)
        val FIELDS = LAYOUT.offsetof(2)

        val SIZEOF = LAYOUT.size

        fun create(address: Long) = wrap(GenericFeaturesPNextNode::class.java, address)

        fun create(address: Long, capacity: Int) = wrap(Buffer::class.java, address, capacity)

        fun match(requested: GenericFeaturesPNextNode, supported: GenericFeaturesPNextNode): Boolean {
            assert(requested.sType() == supported.sType()) { "Non-matching sTypes in feature nodes" }
            for(i in 0 until FIELD_CAPACITY) {
                if(requested.field(i) && !supported.field(i))   return false
            }
            return true
        }

        fun malloc(capacity: Int): Buffer {
            return wrap(Buffer::class.java, stackGet().nmalloc(capacity * SIZEOF), capacity)
        }

        fun calloc(): GenericFeaturesPNextNode {
            return wrap(GenericFeaturesPNextNode::class.java, nmemCalloc(1, SIZEOF.toLong()))
        }
    }

    override fun sizeof() = SIZEOF

    fun <T : Struct> set(features: T) = memCopy(features.address(), address, min(SIZEOF, features.sizeof()).toLong())

    fun sType() = memGetInt(address + STYPE)
    fun field(idx: Int) = memGetInt(address + FIELDS * idx * 4) != 0

    fun pNext(pNext: Long) = memPutAddress(address + PNEXT, pNext)

    class Buffer(container: ByteBuffer) :
        StructBuffer<GenericFeaturesPNextNode, Buffer>(container, container.remaining() / SIZEOF),
        NativeResource {

        companion object {
            val ELEMENT_FACTORY: GenericFeaturesPNextNode = create(-1L)
        }

        override fun self(): Buffer {
            return this
        }

        override fun getElementFactory(): GenericFeaturesPNextNode {
            return ELEMENT_FACTORY
        }

    }
}