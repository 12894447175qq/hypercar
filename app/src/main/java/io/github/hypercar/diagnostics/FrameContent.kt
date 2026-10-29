package io.github.hypercar.diagnostics

internal data class FrameContent(val minLuma:Int,val maxLuma:Int,val meanLuma:Double,val darkFraction:Double,val nearBlack:Boolean) {
    companion object {
        fun inspect(y:ByteArray,u:ByteArray,v:ByteArray,fullRange:Boolean):FrameContent {
            require(y.isNotEmpty() && u.isNotEmpty() && v.isNotEmpty())
            var min=255;var max=0;var total=0L;var dark=0
            val threshold=if(fullRange)8 else 24
            for(b in y){val n=b.toInt() and 255;min=minOf(min,n);max=maxOf(max,n);total+=n;if(n<=threshold)dark++}
            val neutral=(u.count{kotlin.math.abs((it.toInt() and 255)-128)<=8}+v.count{kotlin.math.abs((it.toInt() and 255)-128)<=8}).toDouble()/(u.size+v.size)
            val ratio=dark.toDouble()/y.size
            return FrameContent(min,max,total.toDouble()/y.size,ratio,ratio>=0.995 && neutral>=0.995)
        }
    }
}
