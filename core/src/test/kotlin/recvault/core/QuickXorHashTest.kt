package recvault.core

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/** Vectors copied from rclone backend/onedrive/quickxorhash/quickxorhash_test.go */
class QuickXorHashTest {
    private val vectors = listOf(
        "" to "AAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "Sg==" to "SgAAAAAAAAAAAAAAAQAAAAAAAAA=",
        "tbQ=" to "taAFAAAAAAAAAAAAAgAAAAAAAAA=",
        "0pZP" to "0rDEEwAAAAAAAAAAAwAAAAAAAAA=",
        "jRRDVA==" to "jaDAEKgAAAAAAAAABAAAAAAAAAA=",
        "eAV52qE=" to "eChAHrQRCgAAAAAABQAAAAAAAAA=",
        "luBZlaT6" to "lgBHFipBCn0AAAAABgAAAAAAAAA=",
        "qaApEj66lw==" to "qQBFCiTgA11cAgAABwAAAAAAAAA=",
        "/aNzzCFPS/A=" to "/RjFHJgRgicsAR4ACAAAAAAAAAA=",
        "n6Neh7p6fFgm" to "nxiFFw6hCz3wAQsmCQAAAAAAAAA=",
        "J9iPGCbfZSTNyw==" to "J8DGIzBggm+UgQTNUgYAAAAAAAA=",
        "i+UZyUGJKh+ISbk=" to "iyhHBpIRhESo4AOIQ0IuAAAAAAA=",
        "h490d57Pqz5q2rtT" to "h3gEHe7giWeswgdq3MYupgAAAAA=",
        "vPgoDjOfO6fm71RxLw==" to "vMAHChwwg0/s4BTmdQcV4vACAAA=",
        "XoJ1AsoR4fDYJrDqYs4=" to "XhBEHQSgjAiEAx7YPgEs1CEGZwA=",
        "gQaybEqS/4UlDc8e4IJm" to "gDCALNigBEn8oxAlZ8AzPAAOQZg=",
        "2fuxhBJXtpWFe8dOfdGeHw==" to "O9tHLAghgSvYohKFyMMxnNCHaHg=",
        "XBV6YKU9V7yMakZnFIxIkuU=" to "HbplHsBQih5cgReMQYMRzkABRiA=",
        "XJZSOiNO2bmfKnTKD7fztcQX" to "/6ZArHQwAidkIxefQgEdlPGAW8w=",
        "g8VtAh+2Kf4k0kY5tzji2i2zmA==" to "wDNrgwHWAVukwB8kg4YRcnALHIg=",
        "T6LYJIfDh81JrAK309H2JMJTXis=" to "zBTHrspn3mEcohlJdIUAbjGNaNg=",
        "DWAAX5/CIfrmErgZa8ot6ZraeSbu" to "LR2Z0PjuRYGKQB/mhQAuMrAGZbQ=",
        "NbYXsp5/K6mR+NmHwExjvWeWDJFnXTKWVlzYHoesp2E=" to "wjuAuWDiq04qDt1R8hHWDDcwVoQ=",
        "qQ70RB++JAR5ljNv3lJt1PpqETPsckopfonItu18Cr3E" to "FkJaeg/0Z5+euShYlLpE2tJh+Lo=",
        "YnF4smoy9hox2jBlJ3VUa4qyCRhOZbWcmFGIiszTT4zAdYHsqJazyg==" to "arkIn+ELddmE8N34J9ydyFKW+9w=",
        "VXs4t4tfXGiWAL6dlhEMm0YQF0f2w9rzX0CvIVeuW56o6/ec2auMpKeU2VeteEK5" to "sq24lSf7wXLH8eigHl07X+qPTps=",
        "sa6SHK9z/G505bysK5KgRO2z2cTksDkLoFc7sv0tWBmf2G2mCiozf2Ce6EIO+W1fRsrrtn/eeOAV" to "xZg1CwMNAjN0AIXw2yh4+1N3oos=",
        ("Mb7EGva2rEE5fENDL85P+BsapHEEjv2/siVhKjvAQe02feExVOQSkfmuYzU/kTF1MaKjPmKF/w+c" +
            "bvwfdWL8aQ==") to "n1anP5NfvD4XDYWIeRPW3ZkPv1Y=",
        ("jyibxJSzO6ZiZ0O1qe3tG/bvIAYssvukh9suIT5wEy1JBINVgPiqdsTW0cOpP0aUfP7mgqLfADkz" +
            "I/m/GgCuVhr8oFLrOCoTx1/psBOWwhltCbhUx51Icm9aH8tY4Z3ccU+6BKpYQkLCy0B/A9Zc") to "hZfLIilSITC6N3e3tQ/iSgEzkto=",
        ("ikwCorI7PKWz17EI50jZCGbV9JU2E8bXVfxNMg5zdmqSZ2NlsQPp0kqYIPjzwTg1MBtfWPg53k0h" +
            "0P2naJNEVgrqpoHTfV2b3pJ4m0zYPTJmUX4Bg/lOxcnCxAYKU29Y5F0U8Quz7ZXFBEweftXxJ7RS" +
            "4r6N7BzJrPsLhY7hgck=") to "imAoFvCWlDn4yVw3/oq1PDbbm6U=",
        ("PfxMcUd0vIW6VbHG/uj/Y0W6qEoKmyBD0nYebEKazKaKG+UaDqBEcmQjbfQeVnVLuodMoPp7P7TR" +
            "1htX5n2VnkHh22xDyoJ8C/ZQKiSNqQfXvh83judf4RVr9exJCud8Uvgip6aVZTaPrJHVjQhMCp/d" +
            "EnGvqg0oN5OVkM2qqAXvA0teKUDhgNM71sDBVBCGXxNOR2bpbD1iM4dnuT0ey4L+loXEHTL0fqMe" +
            "UcEi2asgImnlNakwenDzz0x57aBwyq3AspCFGB1ncX4yYCr/OaCcS5OKi/00WH+wNQU3") to "QX/YEpG0gDsmhEpCdWhsxDzsfVE=",
        ("qwGf2ESubE5jOUHHyc94ORczFYYbc2OmEzo+hBIyzJiNwAzC8PvJqtTzwkWkSslgHFGWQZR2BV5+" +
            "uYTrYT7HVwRM40vqfj0dBgeDENyTenIOL1LHkjtDKoXEnQ0mXAHoJ8PjbNC93zi5TovVRXTNzfGE" +
            "s5dpWVqxUzb5lc7dwkyvOluBw482mQ4xrzYyIY1t+//OrNi1ObGXuUw2jBQOFfJVj2Y6BOyYmfB1" +
            "y36eBxi3zxeG5d5NYjm2GSh6e08QMAwu3zrINcqIzLOuNIiGXBtl7DjKt7b5wqi4oFiRpZsCyx2s" +
            "mhSrdrtK/CkdU6nDN+34vSR/M8rZpWQdBE7a8g==") to "WYT9JY3JIo/pEBp+tIM6Gt2nyTM=",
        ("w0LGhqU1WXFbdavqDE4kAjEzWLGGzmTNikzqnsiXHx2KRReKVTxkv27u3UcEz9+lbMvYl4xFf2Z4" +
            "aE1xRBBNd1Ke5C0zToSaYw5o4B/7X99nKK2/XaUX1byLow2aju2XJl2OpKpJg+tSJ2fmjIJTkfuY" +
            "Uz574dFX6/VXxSxwGH/xQEAKS5TCsBK3CwnuG1p5SAsQq3gGVozDWyjEBcWDMdy8/AIFrj/y03Lf" +
            "c/RNRCQTAfZbnf2QwV7sluw4fH3XJr07UoD0YqN+7XZzidtrwqMY26fpLZnyZjnBEt1FAZWO7RnK" +
            "G5asg8xRk9YaDdedXdQSJAOy6bWEWlABj+tVAigBxavaluUH8LOj+yfCFldJjNLdi90fVHkUD/m4" +
            "Mr5OtmupNMXPwuG3EQlqWUVpQoYpUYKLsk7a5Mvg6UFkiH596y5IbJEVCI1Kb3D1") to "e3+wo77iKcILiZegnzyUNcjCdoQ=",
    )

    @Test fun wholeInput() {
        for ((inB64, out) in vectors) {
            val input = Base64.getDecoder().decode(inB64)
            val h = QuickXorHash().apply { update(input) }
            assertEquals(out, h.digestBase64(), "size ${input.size}")
        }
    }

    @Test fun streamedInBlocks() {
        for (block in listOf(1, 2, 3, 4, 7, 8, 16, 32, 64, 128, 159, 160, 161, 256, 512)) {
            for ((inB64, out) in vectors) {
                val input = Base64.getDecoder().decode(inB64)
                val h = QuickXorHash()
                var i = 0
                while (i < input.size) {
                    val n = minOf(block, input.size - i)
                    h.update(input, i, n); i += n
                }
                assertEquals(out, h.digestBase64(), "size ${input.size} block $block")
            }
        }
    }

    @Test fun hashStreamAgreesWithDirect() {
        val rnd = java.util.Random(7)
        val big = ByteArray(3_000_017).also { rnd.nextBytes(it) }
        val direct = QuickXorHash().apply { update(big) }.digestBase64()
        val viaStream = hashStream(big.inputStream())
        assertEquals(direct, viaStream.quickXorB64)
        assertEquals(big.size.toLong(), viaStream.size)
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(big).joinToString("") { "%02x".format(it) }
        assertEquals(md5, viaStream.md5Hex)
    }
}
