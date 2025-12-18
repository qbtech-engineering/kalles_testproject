package com.apper.model

import kotlinx.serialization.Serializable
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter


class DataClasses {
    @Serializable
    data class Application(val name: String)

    @Serializable
    data class Deployment(val applicationName: String,
                          val version: String,
                          val deployer: String,
                          val env : String)

    @Serializable
    data class DeploymentResponse(
        val application: String,
        val version: String,
        val timestamp: Long,
        val dateTime: String,
        val deployer: String,
        val env : String)
}

fun String.av() = AttributeValue.builder().s(this).build()
fun Long.av() = AttributeValue.builder().n(this.toString()).build()

fun Long.toIsoString(): String {
    return Instant.ofEpochMilli(this)
        .atOffset(ZoneOffset.UTC)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}