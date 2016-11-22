/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.api.impl

import org.scalatest.{FeatureSpec, Matchers, RobolectricTests}

class UsernamesSpec extends FeatureSpec with Matchers with RobolectricTests {
  val usernames = new Usernames
  scenario ("Username pokemon_master354 should be valid") {
    usernames.isUsernameValid("pokemon_master354").isValid should be(true)
  }

  scenario ("Username CatZ+MasteR should be invalid") {
    usernames.isUsernameValid("CatZ_MasteR").isValid should be(false)
  }

  scenario ("Username shiny+ufo should be invalid") {
    usernames.isUsernameValid("shiny+ufo").isValid should be(false)
  }

  scenario ("Username super_long_username_because_whatever should be invalid") {
    usernames.isUsernameValid("super_long_username_because_whatever").isValid should be(false)
  }
}