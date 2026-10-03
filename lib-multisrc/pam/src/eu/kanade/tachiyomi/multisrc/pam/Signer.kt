package eu.kanade.tachiyomi.multisrc.pam

import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.ValType

class Signer(wasm: ByteArray) {

    private val instance = Instance.builder(Parser.parse(wasm))
        .withImportValues(
            ImportValues.builder()
                .addFunction(
                    // emscripten_resize_heap; the signer never outgrows its initial memory.
                    HostFunction(
                        RESIZE_HEAP_MODULE,
                        RESIZE_HEAP_NAME,
                        FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
                    ) { _, _ -> longArrayOf(0) },
                )
                .addFunction(
                    HostFunction(
                        RESIZE_HEAP_MODULE,
                        DEOBFUSCATE_NAME,
                        FunctionType.of(listOf(ValType.I32), emptyList()),
                    ) { instance, args ->
                        val ptr = args[0].toInt()
                        val input = instance.memory().readBytes(ptr, DEOBFUSCATE_SIZE)
                        val output = ByteArray(DEOBFUSCATE_SIZE) { index ->
                            val transformed =
                                (input[DEOBFUSCATE_PERMUTATION[index]].toInt() and 0xff) xor
                                    DEOBFUSCATE_XOR[index]
                            ((transformed + DEOBFUSCATE_ADD[index]) and 0xff).toByte()
                        }
                        instance.memory().write(ptr, output)
                        longArrayOf()
                    },
                )
                .build(),
        )
        .build()
        .also { it.export(CTORS).apply() }

    private val memory = instance.memory()

    /** A block of the signer's heap, as the pointer and length pair its exports expect. */
    class Buf(val ptr: Int, val size: Int)

    /** Runs [block], freeing every [Buf] it allocated once it returns. */
    fun <T> use(block: Session.() -> T): T {
        val session = Session()
        try {
            return session.block()
        } finally {
            session.release()
        }
    }

    inner class Session internal constructor() {

        private val allocated = ArrayList<Int>()

        fun alloc(size: Int): Buf {
            val ptr = instance.export(MALLOC).apply(size.toLong())[0].toInt()
            allocated += ptr
            return Buf(ptr, size)
        }

        fun write(bytes: ByteArray): Buf = alloc(bytes.size).also { memory.write(it.ptr, bytes) }

        fun write(text: String): Buf = write(text.toByteArray())

        fun read(buf: Buf): ByteArray = memory.readBytes(buf.ptr, buf.size)

        fun readText(buf: Buf): String = String(read(buf))

        /**
         * Calls an export by its name in the module. [Buf] arguments pass their pointer, so
         * lengths have to be passed explicitly, like the reader's own glue code does.
         */
        fun call(export: String, vararg args: Any): Long {
            val raw = LongArray(args.size) { i ->
                when (val arg = args[i]) {
                    is Buf -> arg.ptr.toLong()
                    is Double -> arg.toRawBits()
                    is Number -> arg.toLong()
                    else -> throw IllegalArgumentException("unsupported argument: $arg")
                }
            }
            return instance.export(export).apply(*raw).firstOrNull() ?: 0L
        }

        internal fun release() {
            val free = instance.export(FREE)
            allocated.forEach { free.apply(it.toLong()) }
            allocated.clear()
        }
    }

    private companion object {
        const val RESIZE_HEAP_MODULE = "a"
        const val RESIZE_HEAP_NAME = "a"
        const val DEOBFUSCATE_NAME = "b"
        const val DEOBFUSCATE_SIZE = 64
        const val CTORS = "d"
        const val MALLOC = "j"
        const val FREE = "f"

        val DEOBFUSCATE_PERMUTATION = intArrayOf(
            25, 51, 53, 43, 48, 32, 20, 2, 14, 50, 1, 17, 44, 46, 28, 40,
            55, 6, 13, 19, 24, 52, 12, 61, 47, 15, 26, 16, 41, 35, 31, 39,
            38, 10, 9, 8, 29, 22, 54, 11, 59, 34, 30, 37, 5, 57, 4, 18,
            7, 60, 23, 42, 49, 3, 62, 27, 63, 0, 45, 58, 56, 36, 21, 33,
        )

        val DEOBFUSCATE_XOR = intArrayOf(
            39, 116, 128, 77, 37, 233, 90, 232, 108, 11, 121, 58, 89, 14, 193, 145,
            221, 166, 213, 222, 248, 158, 127, 35, 47, 106, 127, 83, 19, 181, 198, 19,
            251, 43, 29, 146, 91, 100, 80, 197, 90, 205, 26, 132, 248, 60, 120, 12,
            175, 164, 159, 176, 130, 119, 121, 113, 167, 181, 138, 159, 16, 43, 252, 135,
        )

        val DEOBFUSCATE_ADD = intArrayOf(
            16, 36, 34, 88, 20, 163, 134, 144, 14, 93, 186, 166, 219, 50, 183, 28,
            104, 204, 107, 82, 121, 187, 251, 31, 18, 3, 39, 215, 183, 32, 144, 58,
            101, 38, 81, 165, 202, 145, 219, 63, 198, 207, 182, 225, 239, 229, 45, 16,
            54, 164, 150, 130, 254, 50, 33, 149, 248, 28, 138, 80, 34, 35, 193, 165,
        )
    }
}
