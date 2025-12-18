package com.apper

import ApplicationRepository
import DeploymentRepository
import DynamoDbFactory.dynamoDb
import com.apper.model.DataClasses
import com.google.gson.Gson
import io.ktor.http.ContentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*

import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType

val logger = LoggerFactory.getLogger("Main")

fun main() {

    embeddedServer(Netty, port = 8080) {
        logger.info("Starting server...")

        val gson = Gson()
        val deploymentRepo = DeploymentRepository(dynamoDb)
        val appRepo = ApplicationRepository(dynamoDb)
        setupDb(dynamoDb)

        routing {
            route("/v1/applications") {
                get {
                    val apps = appRepo.getAll()
                    call.respondText(gson.toJson(apps), ContentType.Application.Json)

                }
                route("/all") {
                    get {
                        val deployments = deploymentRepo.scanAllDeployments(dynamoDb)
                        call.respondText(gson.toJson(deployments), ContentType.Application.Json)
                    }
                }
                route("/deployments") {
                    get {
                        val appName = call.request.queryParameters["application"]
                        val env = call.request.queryParameters["env"]

                        if  (appName != null && env != null ) {
                            logger.info("Fetching deployments for app: $appName in env: $env")
                            val deployments = deploymentRepo.getAllByAppAndEnv(
                                dynamoDb,
                                appName,
                                env
                            )
                            call.respondText(gson.toJson(deployments), ContentType.Application.Json)
                            return@get
                        }

                        if ( appName == null ) {
                            logger.info("Fetching latest deployments for env: ${env ?: "prod"}")
                            val deployments = deploymentRepo.getLatestDeploymentsForEnv(dynamoDb, env ?: "prod")
                            call.respondText(gson.toJson(deployments), ContentType.Application.Json)
                            return@get
                        }
                        logger.info("Fetching deployments for app: $appName")
                        val deployments = deploymentRepo.getByApplication(appName)
                        call.respondText(gson.toJson(deployments), ContentType.Application.Json)
                    }
                    post {
                        val deploymentStr = call.receive<String>()
                        logger.info("Received deployment: $deploymentStr")
                        val deployment = gson.fromJson(deploymentStr, DataClasses.Deployment::class.java)
                        deploymentRepo.putDeployment(deployment)
                        appRepo.putApplication(DataClasses.Application(deployment.applicationName))
                        call.respondText(gson.toJson(deployment), ContentType.Application.Json)
                    }
                }
            }
        }
    }.start(wait = true)
}

fun setupDb(dynamoDb: DynamoDbClient) {
    dropTableIfExists(dynamoDb, "Deployments")
    createApplicationsTable(dynamoDb)
    createDeploymentsTable(dynamoDb)

    waitForTable(dynamoDb, "Deployments")
    waitForTable(dynamoDb, "Applications")
}

private fun createDeploymentsTable(dynamoDb: DynamoDbClient) {
    val request = CreateTableRequest.builder()
        .tableName("Deployments")
        .billingMode(BillingMode.PAY_PER_REQUEST)
        .keySchema(
            KeySchemaElement.builder()
                .attributeName("applicationName")
                .keyType(KeyType.HASH) // PK
                .build(),
            KeySchemaElement.builder()
                .attributeName("env_timestamp")
                .keyType(KeyType.RANGE) // SK
                .build()
        )
        .attributeDefinitions(
            AttributeDefinition.builder()
                .attributeName("applicationName")
                .attributeType(ScalarAttributeType.S)
                .build(),
            AttributeDefinition.builder()
                .attributeName("env_timestamp")
                .attributeType(ScalarAttributeType.S)
                .build(),
            // GSI attributes
            AttributeDefinition.builder()
                .attributeName("env")
                .attributeType(ScalarAttributeType.S)
                .build(),
            AttributeDefinition.builder()
                .attributeName("timestamp")
                .attributeType(ScalarAttributeType.N)
                .build()
        )
        .globalSecondaryIndexes(
            GlobalSecondaryIndex.builder()
                .indexName("EnvIndex")
                .keySchema(
                    KeySchemaElement.builder()
                        .attributeName("env")
                        .keyType(KeyType.HASH) // GSI PK
                        .build(),
                    KeySchemaElement.builder()
                        .attributeName("timestamp")
                        .keyType(KeyType.RANGE) // GSI SK
                        .build()
                )
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build()
        )
        .build()

    createTableIfNotExists(dynamoDb, request)
}

fun createApplicationsTable(dynamoDb: DynamoDbClient) {
    val request = CreateTableRequest.builder()
        .tableName("Applications")
        .billingMode(BillingMode.PAY_PER_REQUEST)
        .keySchema(
            KeySchemaElement.builder()
                .attributeName("name")
                .keyType(KeyType.HASH)
                .build()
        )
        .attributeDefinitions(
            AttributeDefinition.builder()
                .attributeName("name")
                .attributeType(ScalarAttributeType.S)
                .build()
        )
        .build()

    createTableIfNotExists(dynamoDb, request)
}

private fun createTableIfNotExists(
    dynamoDb: DynamoDbClient,
    request: CreateTableRequest
) {
    try {
        dynamoDb.createTable(request)
        logger.info("Creating table ${request.tableName()}")
    } catch (_: ResourceInUseException) {
        // Table already exists
        logger.info("Table ${request.tableName()} already exists")
    }
}

fun dropTableIfExists(dynamoDb: DynamoDbClient, tableName: String) {
    try {
        dynamoDb.deleteTable(
            DeleteTableRequest.builder()
                .tableName(tableName)
                .build()
        )
        println("Deleting table $tableName")
    } catch (e: ResourceNotFoundException) {
        println("Table $tableName does not exist, skipping delete")
    }
}

private fun waitForTable(
    dynamoDb: DynamoDbClient,
    tableName: String
) {
    while (true) {
        val status = dynamoDb.describeTable(
            DescribeTableRequest.builder()
                .tableName(tableName)
                .build()
        ).table().tableStatus()

        if (status == TableStatus.ACTIVE) break
        Thread.sleep(500)
    }
}