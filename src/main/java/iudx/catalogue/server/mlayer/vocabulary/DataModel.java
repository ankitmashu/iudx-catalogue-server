package iudx.catalogue.server.mlayer.vocabulary;

import static iudx.catalogue.server.database.Constants.GET_ALL_DATASETS_BY_RS_GRP;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import iudx.catalogue.server.database.ElasticClient;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DataModel {
  private static final Logger LOGGER = LogManager.getLogger(DataModel.class);
  private final ElasticClient client;
  private final WebClient webClient;
  private final String docIndex;

  /**
   * Constructor for DataModel.
   *
   * @param client The ElasticClient instance
   * @param docIndex The index name where data are stored/retrieved in elastic.
   */
  public DataModel(ElasticClient client, String docIndex) {
    this.webClient = WebClient.create(Vertx.vertx());
    this.client = client;
    this.docIndex = docIndex;
  }

  /**
   * Retrieves data model information asynchronously.
   *
   * @return Future containing JsonObject with class to subclass mappings.
   */
  public Future<JsonObject> getDataModelInfo() {
    Promise<JsonObject> promise = Promise.promise();

    // Get All datasets by resource group
    Future<JsonArray> searchFuture = getAllDatasetsByRsGrp();

    // Process search results when completed and Fetch data models asynchronously
    searchFuture
        .compose(this::fetchDataModels)
        .onComplete(
            ar -> {
              if (ar.succeeded()) {
                promise.complete(ar.result());
              } else {
                promise.complete(new JsonObject());
              }
            });

    return promise.future();
  }

  /**
   * Performs Elasticsearch search asynchronously.
   *
   * @return Future containing JsonArray of search results.
   */
  private Future<JsonArray> getAllDatasetsByRsGrp() {
    Promise<JsonArray> promise = Promise.promise();

    client.searchAsync(
        GET_ALL_DATASETS_BY_RS_GRP,
        docIndex,
        searchHandler -> {
          if (searchHandler.succeeded()) {
            LOGGER.debug("Successful Elastic request");
            JsonObject response = searchHandler.result();
            JsonArray results = response.getJsonArray("results");
            promise.complete(results);
          } else {
            LOGGER.error("Failed Elastic Request: {}", searchHandler.cause().getMessage());
            promise.complete(new JsonArray());
          }
        });

    return promise.future();
  }

  /**
   * Fetches data models asynchronously.
   *
   * @param results The JsonArray of results from Elasticsearch search.
   * @return Future containing JsonObject with class to subclass mappings.
   */
  private Future<JsonObject> fetchDataModels(JsonArray results) {
    Promise<JsonObject> promise = Promise.promise();
    JsonObject classIdToSubClassMap = new JsonObject();

    if (results.isEmpty()) {
      promise.complete(classIdToSubClassMap);
      return promise.future();
    }

    AtomicInteger pendingRequests = new AtomicInteger(results.size());
    String contextUrl = results.getJsonObject(0).getString("@context");

    for (int i = 0; i < results.size(); i++) {
      JsonObject result = results.getJsonObject(i);
      JsonArray typeArray = result.getJsonArray("type");

      if (typeArray == null || typeArray.size() < 2) {
        LOGGER.error("Invalid type array in result: {}", result.encode());
        if (pendingRequests.decrementAndGet() == 0) {
          promise.complete(classIdToSubClassMap);
        }
        continue;
      }

      String id = result.getString("id");
      String type = typeArray.getString(1);
      String classId = type.split(":")[1];
      String dmUrl = contextUrl + classId + ".jsonld";

      webClient
          .getAbs(dmUrl)
          .send(
              dmAr -> {
                handleDataModelResponse(
                    dmAr, id, classId, classIdToSubClassMap, pendingRequests, promise, dmUrl);
              });
    }
    this.webClient.close();
    return promise.future();
  }

  /**
   * Handles the response from fetching data model information.
   *
   * @param dmAr The async result of the HTTP response.
   * @param id The id of the data model.
   * @param classId The class id of the data model.
   * @param classIdToSubClassMap The JsonObject mapping class to subclass.
   * @param pendingRequests The AtomicInteger tracking pending requests.
   * @param promise The Promise to complete with classIdToSubClassMap.
   * @param dmUrl The URL of the data model.
   */
  private void handleDataModelResponse(
      AsyncResult<HttpResponse<Buffer>> dmAr,
      String id,
      String classId,
      JsonObject classIdToSubClassMap,
      AtomicInteger pendingRequests,
      Promise<JsonObject> promise,
      String dmUrl) {
    if (dmAr.succeeded()) {
      HttpResponse<Buffer> dmResponse = dmAr.result();
      Buffer dmBody = dmResponse.body();

      if (dmBody == null) {
        LOGGER.error("No response body received for URL: {}", dmUrl);
      } else if (!dmResponse.headers().get("content-type").contains("application/json")) {
        LOGGER.error("Invalid content-type received for URL: {}", dmUrl);
      } else {
        JsonObject dmJson;
        try {
          dmJson = dmBody.toJsonObject();
        } catch (Exception e) {
          LOGGER.error("Failed to parse JSON response from URL: {}", dmUrl, e);
          dmJson = null;
        }

        if (dmJson != null) {
          JsonArray graph = dmJson.getJsonArray("@graph");

          if (graph != null) {
            for (Object obj : graph) {
              if (obj instanceof JsonObject) {
                JsonObject graphItem = (JsonObject) obj;
                if (("iudx:" + classId).equals(graphItem.getString("@id"))) {
                  JsonObject subClassOfObj = graphItem.getJsonObject("rdfs:subClassOf");
                  if (subClassOfObj != null) {
                    String subClassIdStr = subClassOfObj.getString("@id");
                    if (subClassIdStr != null && subClassIdStr.contains(":")) {
                      String subClassId = subClassIdStr.split(":")[1];
                      classIdToSubClassMap.put(id, subClassId);
                    } else {
                      LOGGER.error("Invalid @id in rdfs:subClassOf for class ID: {}", classId);
                    }
                  } else {
                    LOGGER.error("Missing rdfs:subClassOf for class ID: {}", classId);
                  }
                  break;
                }
              }
            }
          } else {
            LOGGER.error("Invalid graph array in response for URL: {}", dmUrl);
          }
        }
      }
    } else {
      LOGGER.error("Failed to fetch data model for URL: {}", dmUrl, dmAr.cause());
    }

    if (pendingRequests.decrementAndGet() == 0) {
      promise.complete(classIdToSubClassMap);
    }
  }
}
