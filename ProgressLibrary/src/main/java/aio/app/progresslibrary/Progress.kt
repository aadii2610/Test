package aio.app.progresslibrary

import android.content.Context
import android.widget.Toast

class Progress {
    fun simpleToast(context:Context,msg:String){
        Toast.makeText(context, ""+msg, Toast.LENGTH_SHORT).show()
    }
}