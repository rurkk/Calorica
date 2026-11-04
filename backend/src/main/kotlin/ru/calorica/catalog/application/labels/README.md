# Product labels — T13

Authenticated clients list the shared labels; Admin creates, renames and deletes them.
PUT `/products/{id}/labels` replaces a personal product's set; the Admin counterpart
only changes base products. A missing/deleted/foreign product or missing label returns
404. Duplicate labels and sets exceeding 100 are rejected; `[]` explicitly clears.
Product responses and search results include the current UUID-ordered label IDs.
Links survive LEAF/COMPOSITE transitions and soft deletion. Deleting a common label
physically cascades its links across base/personal products, including composite parents.

`ProductLabelRead.distribution(actor, portions, snapshot)` receives the caller's live
read-only REPEATABLE READ snapshot. It calls `CatalogCalculation.allocateLeaves` once,
then groups that projection against `product_label_links` using unconstrained SQL
NUMERIC. There is no new snapshot, recursive SQL or nutrient calculation here.
Repeated roots and shared paths remain separate contributions. Each leaf contributes
to each assigned group; unlabeled leaves contribute no group. Parent labels are ignored
by construction. The returned list is ordered by label UUID; empty groups are absent.
Overlapping groups may sum above consumed grams, while total grams and nutrition remain
the consumer's independent authoritative calculation. Renames preserve IDs; assignment
changes and deletion take effect on the next snapshot, including historical diary data.

Global CRUD/base assignment acquire the existing exclusive base lock. Personal
assignment acquires shared base then owner lock. Validation and replacement happen
after locks in one READ COMMITTED transaction, including the returned Product.
No Spring dependency cycle: CatalogService reads only the labels repository.

Migration 060 is additive; root integration owns the master include. Isolated tests use
`spring.liquibase.change-log=classpath:db/changelog/product-labels-test.yaml` until that
include is integrated. Validation, API rights, leaf grams, overlaps, transitions, diary,
snapshots, deletion races and migration upgrade are covered by the labels tests.
Full live analytics integration belongs to T15; these checks do not claim a deployed release.
