# Пример графа продуктов для презентации

Граф на слайде 5 показывает один связный фрагмент каталога. Весь каталог не обязан
быть связным. Стрелка направлена от составного продукта к его компоненту.
Изображения продуктов, подписи, проценты и связи представлены отдельными объектами
PowerPoint. Изображения кадрируются средствами PowerPoint, исходный атлас сохранён.

Учебный пример, массовые доли условные и не являются рецептом:

| Продукт | Компонент | Массовая доля |
|---|---|---|
| Салат с соусом | Овощная смесь | 80% |
| Салат с соусом | Йогуртовый соус | 20% |
| Овощная смесь | Помидор | 50% |
| Овощная смесь | Огурец | 50% |
| Йогуртовый соус | Огурец | 20% |
| Йогуртовый соус | Йогурт | 80% |

У каждого составного продукта сумма долей равна 100%. Огурец используется в двух
составах, но представлен одним узлом. Есть вложенные составные продукты. Циклов нет.
Пример соответствует [правилам домена](../requirements/domain/product-composition-graph.md).

## Иллюстрации

Атлас: [product-graph-foods.png](product-graph-foods.png).
Создан встроенным ImageGen. Промпт:

> Create one food illustration atlas for a Calorica presentation graph. Exactly SIX isolated illustrations in a precise 3 columns by 2 rows equal-cell grid, landscape 3:2 aspect ratio. Each cell square with illustration centered occupying at most 65% of cell, wide clean margins, NO grid borders, NO text, NO arrows, NO labels. Entire background perfectly flat warm cream #F7F3EA. Consistent tasteful realistic watercolor/gouache food editorial illustration, soft natural muted sage green, tomato red, cream ceramic, minimal shadows. Top row left: ceramic bowl of chopped cucumber and tomato salad with white yogurt dressing mixed in. Top row center: ceramic bowl with only chopped cucumber and tomato, no other ingredient. Top row right: small ceramic bowl of white yogurt sauce with finely diced cucumber visible, no herbs or other ingredients. Bottom row left: single ripe red tomato. Bottom row center: single cucumber with two slices beside it. Bottom row right: small plain ceramic bowl of white natural yogurt, no packaging. All six completely separate and equally scaled. No utensils, no decoration, no typography. Used as cropped food-shaped graph nodes in an editable slide.
