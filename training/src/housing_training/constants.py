from decimal import Decimal

FEATURES = (
    "square_footage",
    "bedrooms",
    "bathrooms",
    "year_built",
    "lot_size",
    "distance_to_city_center",
    "school_rating",
)
SNAPSHOT_COLUMNS = ("source_id", *FEATURES, "price")
TARGET = "price"
RANDOM_SEED = 42
TRAIN_RATIO = Decimal("0.8000")
ACCEPTANCE_POLICY_VERSION = "housing-linear-v1"
METRIC_SCALE = Decimal("0.000000000001")
THRESHOLDS = {
    "mae": Decimal("15000.000000000000"),
    "rmse": Decimal("20000.000000000000"),
    "r2": Decimal("0.900000000000"),
    "mape": Decimal("7.500000000000"),
}
