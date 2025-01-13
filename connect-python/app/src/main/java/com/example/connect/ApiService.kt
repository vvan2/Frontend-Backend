package com.example.connect

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("/predict_pose")
    fun predictPose(@Body poseRequest: PoseRequest): Call<PoseResponse>
}
