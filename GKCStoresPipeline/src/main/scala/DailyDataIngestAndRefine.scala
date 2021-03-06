import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.{StructField, StructType, StringType, IntegerType, TimestampType, DoubleType}
import org.apache.spark.sql.functions.{col, when}
import com.typesafe.config.{Config, ConfigFactory}
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import gkfunctions.read_schema

object DailyDataIngestAndRefine {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().appName("DailyDataIngestAndRefine").master("local[*]")
      .getOrCreate()
    val sc = spark.sparkContext
    import spark.implicits._

    // Reading landing data from Config File

    val gkconfig : Config = ConfigFactory.load("application.conf")
    val inputLocation = gkconfig.getString("paths.inputLocation")
    val outputLocation = gkconfig.getString("paths.outputLocation")

    // Reading Schema from Config
    val landingFileSchemaFromFile = gkconfig.getString("schema.landingFileSchema")
    val holdFileSchemaFromFile = gkconfig.getString("schema.holdFileSchema")
    val landingFileSchema = read_schema(landingFileSchemaFromFile)
    val holdFileSchema = read_schema(holdFileSchemaFromFile)

    // Handling Dates
    val dateToday = LocalDate.now()
    val yesterDate = dateToday.minusDays(1)

    //val currDayZoneSuffix = "_" + dateToday.format(DateTimeFormatter.ofPattern("ddMMyyyy"))
    val currDayZoneSuffix = "_19072020"
    //val prevDayZoneSuffix = "_" + yesterDate.format(DateTimeFormatter.ofPattern("ddMMyyyy"))
    val prevDayZoneSuffix = "_18072020"

    val landingFileDF = spark.read
      .schema(landingFileSchema)
      .option("delimiter", "|")
      .csv(inputLocation + "Sales_Landing/SalesDump" + currDayZoneSuffix)
    landingFileDF.createOrReplaceTempView("landingFileDF")


    // Checking if updates were received on any previous hold data

    val previousHoldDF = spark.read
      .schema(holdFileSchema)
      .option("delimiter", "|")
      .option("header", true)
      .csv(outputLocation + "Hold/HoldData" + prevDayZoneSuffix)
    previousHoldDF.createOrReplaceTempView("previousHoldDF")


    val refreshedLandingData = spark.sql("select a.Sale_ID, a.Product_ID, " +
      "CASE " +
      "WHEN (a.Quantity_Sold IS NULL) THEN b.Quantity_Sold " +
      "ELSE a.Quantity_Sold " +
      "END AS Quantity_Sold, " +
      "CASE " +
      "WHEN (a.Vendor_ID IS NULL) THEN b.Vendor_ID " +
      "ELSE a.Vendor_ID " +
      "END AS Vendor_ID, " +
      "a.Sale_Date, a.Sale_Amount, a.Sale_Currency " +
      "from landingFileDF a left outer join previousHoldDF b on a.Sale_ID = b.Sale_ID ")
    refreshedLandingData.createOrReplaceTempView("refreshedLandingData")
    refreshedLandingData.show()
    val validLandingData = refreshedLandingData.filter(col("Quantity_Sold").isNotNull
      && col("Vendor_ID").isNotNull)
    validLandingData.createOrReplaceTempView("validLandingData")

    val releasedFromHold = spark.sql("select vd.Sale_ID " +
      "from validLandingData vd INNER JOIN previousHoldDF phd " +
      "ON vd.Sale_ID = phd.Sale_ID ")
    releasedFromHold.createOrReplaceTempView("releasedFromHold")


    val notReleasedFromHold = spark.sql("select * from previousHoldDF " +
      "where Sale_ID NOT IN (select Sale_ID from releasedFromHold)")
    notReleasedFromHold.createOrReplaceTempView("notReleasedFromHold")


    val inValidLandingData = refreshedLandingData.filter(col("Quantity_Sold").isNull
      || col("Vendor_ID").isNull)
      .withColumn("Hold_Reason", when(col("Quantity_Sold").isNull, "Qty Sold Missing")
      .otherwise(when(col("Vendor_ID").isNull, "Vendor ID Missing")))
      .union(notReleasedFromHold)

    validLandingData.write
        .mode("overwrite")
        .option("delimiter", "|")
        .option("header", true)
        .csv(outputLocation + "Valid/ValidData" + currDayZoneSuffix)

    inValidLandingData.write
      .mode("overwrite")
      .option("delimiter", "|")
      .option("header", true)
      .csv(outputLocation + "Hold/HoldData" + currDayZoneSuffix)

  }

}
