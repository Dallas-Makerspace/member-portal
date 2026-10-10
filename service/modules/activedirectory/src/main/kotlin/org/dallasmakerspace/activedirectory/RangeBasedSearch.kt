package org.dallasmakerspace.activedirectory

import com.unboundid.ldap.sdk.Attribute
import com.unboundid.ldap.sdk.LDAPConnectionPool
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.SearchRequest
import com.unboundid.ldap.sdk.SearchResultEntry
import com.unboundid.ldap.sdk.SearchScope
import io.ktor.util.logging.*
import org.dallasmakerspace.core.CoreServices
import org.dallasmakerspace.core.LoggerFactory

object RangeBasedSearch {
  val loggerFactory: LoggerFactory by lazy { CoreServices.loggerFactory }
  val log: Logger by lazy { loggerFactory.create(RangeBasedSearch::class.java) }

  /**
   * We can dynamically get the range step value if we get retrieve all attribute names. The range
   * step will be in an attribute like: member;range=0-1499
   *
   * @param idv_searchldc
   * @param basedn
   * @param filter
   * @param return_attribute
   * @return
   * @throws LDAPException
   */
  @Throws(LDAPException::class)
  fun getRangeStepValue(
      idv_searchldc: LDAPConnectionPool,
      basedn: String?,
      filter: String?,
      return_attribute: String
  ): Int {
    val searchRequest =
        SearchRequest(basedn, SearchScope.BASE, filter, SearchRequest.ALL_USER_ATTRIBUTES)
    val rangedEntries: List<SearchResultEntry> = idv_searchldc.search(searchRequest).searchEntries
    val iterator: Iterator<SearchResultEntry> = rangedEntries.iterator()
    while (iterator.hasNext()) {
      val searchResultEntry: SearchResultEntry = iterator.next()
      val allAttribute: Collection<Attribute> = searchResultEntry.getAttributes()
      val attributeIterator = allAttribute.iterator()
      while (attributeIterator.hasNext()) {
        val attribute = attributeIterator.next()
        log.debug("---> " + attribute.name)
        val rangeCheckAttribute = "$return_attribute;range=0-"
        if (attribute.name.contains(rangeCheckAttribute)) {
          val rangeStep =
              attribute.name
                  .substring(
                      attribute.name.lastIndexOf(rangeCheckAttribute) + rangeCheckAttribute.length)
                  .toInt()
          log.debug("Range Step is: $rangeStep")
          return rangeStep
        } else {
          return 0 // rangebasedSearchNot Needed
        }
      }
    }
    return 0
  }

  @Throws(LDAPException::class)
  fun getAttributeRangeBasedSearch(
      ldc: LDAPConnectionPool,
      basedn: String?,
      filter: String?,
      return_attribute: String
  ): List<String> {
    val step = getRangeStepValue(ldc, basedn, filter, return_attribute)
    return getAttributeRangeBasedSearch(ldc, basedn, filter, return_attribute, step)
  }

  /**
   * @param ldc
   * @param basedn
   * @param filter
   * @param step
   * - How can we dynamically determine the step?
   *
   * @param return_attribute
   * @return
   * @throws LDAPException
   */
  @Throws(LDAPException::class)
  fun getAttributeRangeBasedSearch(
      ldc: LDAPConnectionPool,
      basedn: String?,
      filter: String?,
      return_attribute: String,
      step: Int
  ): List<String> {
    val initialStep = step
    val allValues: MutableList<String> = ArrayList()
    // initialize counter to total the return_attribute values and range values
    var start = 0
    var finish = step
    var finallyFinished = false
    var range: String
    // loop through the query until we have all the results
    while (!finallyFinished) {
      range = "$start-$finish"
      var currentRange: String? = null
      currentRange =
          if (step == 0) {
            return_attribute
          } else {
            "$return_attribute;Range=$range"
          }
      val range_returnedAtts = arrayOf<String?>(currentRange)
      val searchRequest = SearchRequest(basedn, SearchScope.BASE, filter, *range_returnedAtts)
      val rangedEntries: List<SearchResultEntry> = ldc.search(searchRequest).searchEntries
      val iterator: Iterator<SearchResultEntry> = rangedEntries.iterator()
      while (iterator.hasNext()) {
        val searchResultEntry: SearchResultEntry = iterator.next()
        val allAttribute: Collection<Attribute> = searchResultEntry.getAttributes()
        val attributeIterator = allAttribute.iterator()
        while (attributeIterator.hasNext()) {
          val attribute = attributeIterator.next()
          log.debug("---> " + attribute.name)
          // The last batch returns this as member;range=28500-*
          if (attribute.name.endsWith("*") || step == 0) {
            currentRange = attribute.name
            finallyFinished = true
          }
          val attributeBatch: Array<String> = searchResultEntry.getAttributeValues(currentRange)
          for (i in attributeBatch.indices) {
            allValues.add(attributeBatch[i])
            // log.debug("-- " + allvalues++ + " " + attribute.getName() + ":" + attributeBatch[i]);
          }
        }
      }
      start = finish + 1
      finish = finish + step
    } // finallyFinished

    log.info(
        "Total " +
            return_attribute +
            " Entries found: " +
            allValues.size +
            " ( initialStep=" +
            initialStep +
            ")")
    return allValues
  }
}
