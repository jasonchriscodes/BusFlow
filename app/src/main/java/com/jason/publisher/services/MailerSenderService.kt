import com.jason.publisher.EmailData
import com.jason.publisher.ResponseData
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface MailerSendService {
    @Headers("Content-Type: application/json")
    @POST("email")
    fun sendEmail(@Body emailData: EmailData): Call<ResponseData>
}
